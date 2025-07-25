package coop.rchain.rholang.interpreter

import akka.actor.ActorSystem
import akka.stream.Materializer
import cats.effect.{Concurrent, Sync}
import cats.effect.concurrent.Ref
import cats.syntax.all._
import com.google.protobuf.ByteString
import com.typesafe.scalalogging.Logger
import coop.rchain.casper.protocol.BlockMessage
import coop.rchain.crypto.PublicKey
import coop.rchain.crypto.hash.{Blake2b256, Keccak256, Sha256}
import coop.rchain.crypto.signatures.{Ed25519, Secp256k1}
import coop.rchain.metrics.Span
import coop.rchain.models.Expr.ExprInstance.GString
import coop.rchain.models.GUnforgeable.UnfInstance
import coop.rchain.models.GUnforgeable.UnfInstance.GPrivateBody
import coop.rchain.models.TaggedContinuation.TaggedCont.ScalaBodyRef
import coop.rchain.models._
import coop.rchain.models.rholang.implicits._
import coop.rchain.rholang.interpreter.RhoRuntime.RhoTuplespace
import coop.rchain.rholang.interpreter.registry.Registry
import coop.rchain.rholang.interpreter.RholangAndScalaDispatcher.RhoDispatch
import coop.rchain.rholang.interpreter.util.RevAddress
import coop.rchain.rspace.{ContResult, Result}
import coop.rchain.shared.Base16
import io.cequence.openaiscala.domain.ModelId
import io.cequence.openaiscala.domain.settings.CreateCompletionSettings
import io.cequence.openaiscala.service.OpenAIServiceFactory

import java.io.{File, FileOutputStream, FileWriter}
import java.nio.file.Files
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Random, Try}

//TODO: Make each of the system processes into a case class,
//      so that implementation is not repetitive.
//TODO: Make polymorphic over match type.
trait SystemProcesses[F[_]] {
  import SystemProcesses.Contract

  def stdOut: Contract[F]
  def stdOutAck: Contract[F]
  def stdErr: Contract[F]
  def stdErrAck: Contract[F]
  def random: Contract[F]
  def secp256k1Verify: Contract[F]
  def ed25519Verify: Contract[F]
  def sha256Hash: Contract[F]
  def keccak256Hash: Contract[F]
  def blake2b256Hash: Contract[F]
  def getBlockData(blockData: Ref[F, SystemProcesses.BlockData]): Contract[F]
  def invalidBlocks(invalidBlocks: SystemProcesses.InvalidBlocks[F]): Contract[F]
  def revAddress: Contract[F]
  def deployerIdOps: Contract[F]
  def registryOps: Contract[F]
  def sysAuthTokenOps: Contract[F]
  def gpt3: Contract[F]
  def gpt4: Contract[F]
  def dalle3: Contract[F]
  def textToAudio: Contract[F]
  def dumpFile: Contract[F]
  def grpcTell: Contract[F]
}

object SystemProcesses {
  type RhoSysFunction[F[_]] = (Seq[ListParWithRandom], Boolean, Seq[Par]) => F[Seq[Par]]
  type RhoDispatchMap[F[_]] = Map[Long, RhoSysFunction[F]]
  type Name                 = Par
  type Arity                = Int
  type Remainder            = Option[Var]
  type BodyRef              = Long
  class InvalidBlocks[F[_]](val invalidBlocks: Ref[F, Par]) {
    def setParams(invalidBlocks: Par): F[Unit] =
      this.invalidBlocks.set(invalidBlocks)
  }

  object InvalidBlocks {
    def apply[F[_]]()(implicit F: Sync[F]): F[InvalidBlocks[F]] =
      for {
        invalidBlocks <- Ref[F].of(Par())
      } yield new InvalidBlocks[F](invalidBlocks)

    def unsafe[F[_]]()(implicit F: Sync[F]): InvalidBlocks[F] =
      new InvalidBlocks(Ref.unsafe[F, Par](Par()))
  }

  final case class BlockData private (
      timeStamp: Long,
      blockNumber: Long,
      sender: PublicKey,
      seqNum: Int
  )

  def byteName(b: Byte): Par = GPrivate(ByteString.copyFrom(Array[Byte](b)))

  object FixedChannels {
    val STDOUT: Par             = byteName(0)
    val STDOUT_ACK: Par         = byteName(1)
    val STDERR: Par             = byteName(2)
    val STDERR_ACK: Par         = byteName(3)
    val ED25519_VERIFY: Par     = byteName(4)
    val SHA256_HASH: Par        = byteName(5)
    val KECCAK256_HASH: Par     = byteName(6)
    val BLAKE2B256_HASH: Par    = byteName(7)
    val SECP256K1_VERIFY: Par   = byteName(8)
    val GET_BLOCK_DATA: Par     = byteName(10)
    val GET_INVALID_BLOCKS: Par = byteName(11)
    val REV_ADDRESS: Par        = byteName(12)
    val DEPLOYER_ID_OPS: Par    = byteName(13)
    val REG_LOOKUP: Par         = byteName(14)
    val REG_INSERT_RANDOM: Par  = byteName(15)
    val REG_INSERT_SIGNED: Par  = byteName(16)
    val REG_OPS: Par            = byteName(17)
    val SYS_AUTHTOKEN_OPS: Par  = byteName(18)
    val GPT3: Par               = byteName(19)
    val GPT4: Par               = byteName(20)
    val DALLE3: Par             = byteName(21)
    val TEXT_TO_AUDIO: Par      = byteName(22)
    val DUMP: Par               = byteName(23)
    val RANDOM: Par             = byteName(24)
    val GRPC_TELL: Par          = byteName(25)
  }
  object BodyRefs {
    val STDOUT: Long             = 0L
    val STDOUT_ACK: Long         = 1L
    val STDERR: Long             = 2L
    val STDERR_ACK: Long         = 3L
    val ED25519_VERIFY: Long     = 4L
    val SHA256_HASH: Long        = 5L
    val KECCAK256_HASH: Long     = 6L
    val BLAKE2B256_HASH: Long    = 7L
    val SECP256K1_VERIFY: Long   = 9L
    val GET_BLOCK_DATA: Long     = 11L
    val GET_INVALID_BLOCKS: Long = 12L
    val REV_ADDRESS: Long        = 13L
    val DEPLOYER_ID_OPS: Long    = 14L
    val REG_OPS: Long            = 15L
    val SYS_AUTHTOKEN_OPS: Long  = 16L
    val GPT3: Long               = 17L
    val GPT4: Long               = 18L
    val DALLE3: Long             = 19L
    val TEXT_TO_AUDIO: Long      = 20L
    val DUMP: Long               = 21L
    val RANDOM: Long             = 22L
    val GRPC_TELL: Long          = 23L
  }

  val nonDeterministicCalls: Set[Long] = Set(
    BodyRefs.RANDOM,
    BodyRefs.GPT3,
    BodyRefs.GPT4,
    BodyRefs.DALLE3,
    BodyRefs.TEXT_TO_AUDIO
  )

  final case class ProcessContext[F[_]: Concurrent: Span](
      space: RhoTuplespace[F],
      dispatcher: RhoDispatch[F],
      blockData: Ref[F, BlockData],
      invalidBlocks: InvalidBlocks[F],
      openAIService: OpenAIService
  ) {
    val systemProcesses = SystemProcesses[F](dispatcher, space, openAIService)
  }
  final case class Definition[F[_]](
      urn: String,
      fixedChannel: Name,
      arity: Arity,
      bodyRef: BodyRef,
      handler: ProcessContext[F] => (Seq[ListParWithRandom], Boolean, Seq[Par]) => F[Seq[Par]],
      remainder: Remainder = None
  ) {
    def toDispatchTable(
        context: ProcessContext[F]
    ): (BodyRef, (Seq[ListParWithRandom], Boolean, Seq[Par]) => F[Seq[Par]]) =
      bodyRef -> handler(context)

    def toUrnMap: (String, Par) = {
      val bundle: Par = Bundle(fixedChannel, writeFlag = true)
      urn -> bundle
    }

    def toProcDefs: (Name, Arity, Remainder, BodyRef) =
      (fixedChannel, arity, remainder, bodyRef)
  }
  object BlockData {
    def empty: BlockData = BlockData(0, 0, PublicKey(Base16.unsafeDecode("00")), 0)
    def fromBlock(template: BlockMessage) =
      BlockData(
        template.header.timestamp,
        template.body.state.blockNumber,
        PublicKey(template.sender),
        template.seqNum
      )
  }
  type Contract[F[_]] = (Seq[ListParWithRandom], Boolean, Seq[Par]) => F[Seq[Par]]

  def apply[F[_]](
      dispatcher: Dispatch[F, ListParWithRandom, TaggedContinuation],
      space: RhoTuplespace[F],
      openAIService: OpenAIService
  )(implicit F: Concurrent[F], spanF: Span[F]): SystemProcesses[F] =
    new SystemProcesses[F] {

      type ContWithMetaData = ContResult[Par, BindPattern, TaggedContinuation]

      type Channels = Seq[Result[Par, ListParWithRandom]]

      private val prettyPrinter = PrettyPrinter()

      private val isContractCall = new ContractCall[F](space, dispatcher)

      private val stdOutLogger = Logger("coop.rchain.rholang.stdout")
      private val stdErrLogger = Logger("coop.rchain.rholang.stderr")

      private def illegalArgumentException(msg: String): F[Seq[Par]] =
        F.raiseError(new IllegalArgumentException(msg))

      def verifySignatureContract(
          name: String,
          algorithm: (Array[Byte], Array[Byte], Array[Byte]) => Boolean
      ): Contract[F] = {
        case isContractCall(
            produce,
            _,
            _,
            Seq(
              RhoType.ByteArray(data),
              RhoType.ByteArray(signature),
              RhoType.ByteArray(pub),
              ack
            )
            ) =>
          for {
            verified <- F.fromTry(Try(algorithm(data, signature, pub)))
            output   = Seq(RhoType.Boolean(verified): Par)
            _        <- produce(output, ack)
          } yield output
        case _ =>
          illegalArgumentException(
            s"$name expects data, signature, public key (all as byte arrays), and an acknowledgement channel"
          )
      }

      def hashContract(name: String, algorithm: Array[Byte] => Array[Byte]): Contract[F] = {
        case isContractCall(produce, _, _, Seq(RhoType.ByteArray(input), ack)) =>
          for {
            hash   <- F.fromTry(Try(algorithm(input)))
            output = Seq(RhoType.ByteArray(hash))
            _      <- produce(output, ack)
          } yield output
        case _ =>
          illegalArgumentException(
            s"$name expects a byte array and return channel"
          )
      }

      private def printStdOut(s: String): F[Seq[Par]] =
        for {
          _ <- F.delay(Console.println(s))
          _ <- F.delay(stdOutLogger.debug(s))
        } yield Seq.empty[Par]

      private def printStdErr(s: String): F[Seq[Par]] =
        for {
          _ <- F.delay(Console.err.println(s))
          _ <- F.delay(stdErrLogger.debug(s))
        } yield Seq.empty[Par]

      def stdOut: Contract[F] = {
        case isContractCall(_, _, _, Seq(arg)) =>
          printStdOut(prettyPrinter.buildString(arg))
      }

      def stdOutAck: Contract[F] = {
        case isContractCall(produce, _, _, Seq(arg, ack)) =>
          for {
            _      <- printStdOut(prettyPrinter.buildString(arg))
            output = Seq(Par.defaultInstance)
            _      <- produce(output, ack)
          } yield output
      }

      def stdErr: Contract[F] = {
        case isContractCall(_, _, _, Seq(arg)) =>
          printStdErr(prettyPrinter.buildString(arg))
      }

      def stdErrAck: Contract[F] = {
        case isContractCall(produce, _, _, Seq(arg, ack)) =>
          for {
            _      <- printStdErr(prettyPrinter.buildString(arg))
            output = Seq(Par.defaultInstance)
            _      <- produce(output, ack)
          } yield output
      }

      def random: Contract[F] = {
        case isContractCall(produce, true, previous: Seq[Par], Seq(ack)) => {
          produce(previous, ack).map(_ => previous)
        }
        case isContractCall(produce, a, b, Seq(ack)) => {
          val random1      = new Random()
          val randomLength = random1.nextInt(100)
          val randomString = Seq.fill(randomLength)(random1.nextPrintableChar()).mkString
          val output       = Seq(RhoType.String(randomString))
          produce(output, ack).map(_ => output)
        }
      }

      def revAddress: Contract[F] = {
        case isContractCall(
            produce,
            _,
            _,
            Seq(RhoType.String("validate"), RhoType.String(address), ack)
            ) =>
          val errorMessage =
            RevAddress
              .parse(address)
              .swap
              .toOption
              .map(RhoType.String(_))
              .getOrElse(Par())

          produce(Seq(errorMessage), ack)

        // TODO: Invalid type for address should throw error!
        case isContractCall(produce, _, _, Seq(RhoType.String("validate"), _, ack)) =>
          produce(Seq(Par()), ack)

        case isContractCall(
            produce,
            _,
            _,
            Seq(RhoType.String("fromPublicKey"), RhoType.ByteArray(publicKey), ack)
            ) =>
          val response =
            RevAddress
              .fromPublicKey(PublicKey(publicKey))
              .map(ra => RhoType.String(ra.toBase58))
              .getOrElse(Par())

          produce(Seq(response), ack)

        case isContractCall(produce, _, _, Seq(RhoType.String("fromPublicKey"), _, ack)) =>
          produce(Seq(Par()), ack)

        case isContractCall(
            produce,
            _,
            _,
            Seq(RhoType.String("fromDeployerId"), RhoType.DeployerId(id), ack)
            ) =>
          val response =
            RevAddress
              .fromDeployerId(id)
              .map(ra => RhoType.String(ra.toBase58))
              .getOrElse(Par())

          produce(Seq(response), ack)

        case isContractCall(produce, _, _, Seq(RhoType.String("fromDeployerId"), _, ack)) =>
          produce(Seq(Par()), ack)

        case isContractCall(
            produce,
            _,
            _,
            Seq(RhoType.String("fromUnforgeable"), argument, ack)
            ) =>
          val response = argument match {
            case RhoType.Name(gprivate) =>
              RhoType.String(RevAddress.fromUnforgeable(gprivate).toBase58)
            case _ => Par()
          }

          produce(Seq(response), ack)

        case isContractCall(produce, _, _, Seq(RhoType.String("fromUnforgeable"), _, ack)) =>
          produce(Seq(Par()), ack)
      }

      def deployerIdOps: Contract[F] = {
        case isContractCall(
            produce,
            _,
            _,
            Seq(RhoType.String("pubKeyBytes"), RhoType.DeployerId(publicKey), ack)
            ) =>
          produce(Seq(RhoType.ByteArray(publicKey)), ack)

        case isContractCall(produce, _, _, Seq(RhoType.String("pubKeyBytes"), _, ack)) =>
          produce(Seq(Par()), ack)
      }

      def registryOps: Contract[F] = {
        case isContractCall(
            produce,
            _,
            _,
            Seq(RhoType.String("buildUri"), argument, ack)
            ) =>
          val response = argument match {
            case RhoType.ByteArray(ba) =>
              val hashKeyBytes = Blake2b256.hash(ba)
              RhoType.Uri(Registry.buildURI(hashKeyBytes))
            case _ => Par()
          }
          produce(Seq(response), ack)
      }

      def sysAuthTokenOps: Contract[F] = {
        case isContractCall(
            produce,
            _,
            _,
            Seq(RhoType.String("check"), argument, ack)
            ) =>
          val response = argument match {
            case RhoType.SysAuthToken(_) => RhoType.Boolean(true)
            case _                       => RhoType.Boolean(false)
          }
          produce(Seq(response), ack)
      }

      def secp256k1Verify: Contract[F] =
        verifySignatureContract("secp256k1Verify", Secp256k1.verify)

      def ed25519Verify: Contract[F] =
        verifySignatureContract("ed25519Verify", Ed25519.verify)

      def sha256Hash: Contract[F] =
        hashContract("sha256Hash", Sha256.hash)

      def keccak256Hash: Contract[F] =
        hashContract("keccak256Hash", Keccak256.hash)

      def blake2b256Hash: Contract[F] =
        hashContract("blake2b256Hash", Blake2b256.hash)

      def gpt3: Contract[F] = {
        case isContractCall(produce, true, previousOutput, Seq(RhoType.String(prompt), ack)) => {
          produce(previousOutput, ack).map(_ => previousOutput)
        }
        case isContractCall(produce, _, _, Seq(RhoType.String(prompt), ack)) => {
          (for {
            response <- openAIService.gpt3TextCompletion(prompt)
            output   = Seq(RhoType.String(response))
            _        <- produce(output, ack)
          } yield output).onError {
            case e =>
              produce(Seq(RhoType.String(prompt)), ack)
              e.raiseError
          }
        }
      }

      def gpt4: Contract[F] = {
        case isContractCall(produce, true, previousOutput, Seq(RhoType.String(prompt), ack)) => {
          produce(previousOutput, ack).map(_ => previousOutput)
        }
        case isContractCall(produce, _, _, Seq(RhoType.String(prompt), ack)) => {
          (for {
            response <- openAIService.gpt4TextCompletion(prompt)
            output   = Seq(RhoType.String(response))
            _        <- produce(output, ack)
          } yield output).onError {
            case e =>
              produce(Seq(RhoType.String(prompt)), ack)
              e.raiseError
          }
        }
      }

      def dalle3: Contract[F] = {
        case isContractCall(produce, true, previousOutput, Seq(RhoType.String(prompt), ack)) => {
          produce(previousOutput, ack).map(_ => previousOutput)
        }
        case isContractCall(produce, _, _, Seq(RhoType.String(prompt), ack)) => {
          (for {
            response <- openAIService.dalle3CreateImage(prompt)
            output   = Seq(RhoType.String(response))
            _        <- produce(output, ack)
          } yield output).onError {
            case e =>
              produce(Seq(RhoType.String(prompt)), ack)
              e.raiseError
          }
        }
      }

      def textToAudio: Contract[F] = {
        case isContractCall(produce, true, previousOutput, Seq(RhoType.String(prompt), ack)) => {
          produce(previousOutput, ack).map(_ => previousOutput)
        }
        case isContractCall(produce, _, _, Seq(RhoType.String(text), ack)) => {
          (for {
            bytes  <- openAIService.ttsCreateAudioSpeech(text)
            output = Seq(RhoType.ByteArray(bytes))
            _      <- produce(output, ack)
          } yield output).onError {
            case e =>
              produce(Seq(RhoType.String(text)), ack)
              e.raiseError
          }
        }
      }

      def dumpFile: Contract[F] = {
        case isContractCall(_, _, _, Seq(RhoType.String(path), RhoType.ByteArray(data))) => {
          F.delay {
              Files.write(new File(path).toPath, data)
              Seq.empty[Par]
            }
            .onError {
              case e => F.delay(Console.err.println(s"Error writing to file: $e"))
            }
        }
      }

      override def grpcTell: Contract[F] = {
        case isContractCall(_, true, previous, args) =>
          // args could be:
          // - clientHost, clientPort, folderId, ack
          // - clientHost, clientPort, folderId, error, ack if failed previously

          // so using the last element as ack
          F.delay(previous)

        case isContractCall(
            _,
            false,
            _,
            Seq(
              RhoType.String(clientHost),
              RhoType.Number(clientPort),
              RhoType.String(notificationPayload)
            )
            ) =>
          for {
            _ <- GrpcClient.initClientAndTell(clientHost, clientPort, notificationPayload).recover {
                  case e => Console.err.println("GrpcClient crashed: " + e.getMessage)
                }
            output = Seq(RhoType.Nil())
          } yield output
        case isContractCall(_, isReplay, _, args) =>
          F.delay(Seq(RhoType.Nil()))
      }

      def getBlockData(
          blockData: Ref[F, BlockData]
      ): Contract[F] = {
        case isContractCall(produce, _, _, Seq(ack)) =>
          for {
            data <- blockData.get
            output = Seq(
              RhoType.Number(data.blockNumber),
              RhoType.Number(data.timeStamp),
              RhoType.ByteArray(data.sender.bytes)
            )
            _ <- produce(
                  output,
                  ack
                )
          } yield output
        case _ =>
          illegalArgumentException("blockData expects only a return channel")
      }

      def invalidBlocks(invalidBlocks: InvalidBlocks[F]): Contract[F] = {
        case isContractCall(produce, _, _, Seq(ack)) =>
          for {
            invalidBlocks <- invalidBlocks.invalidBlocks.get
            output        = Seq(invalidBlocks)
            _             <- produce(output, ack)
          } yield output
        case _ =>
          illegalArgumentException("invalidBlocks expects only a return channel")
      }
    }
}
