import os
from pathlib import Path
from typing import Iterable

import yaml

GITHUB_DIR = os.path.dirname(os.path.abspath(__file__))
WORKFLOW_PATH = GITHUB_DIR + '/workflows/continuous-integration.yml'
REMAINDER = 'REMAINDER'


def get_project_root() -> Path:
    """Returns project root path from PROJECT_ROOT environment variable or
    falls back to current working directory"""
    return Path(os.getenv('PROJECT_ROOT', os.getcwd()))


def read_workflow(workflow_path: Path) -> dict:
    """Reads YAML workflow as dict"""
    with open(workflow_path) as f:
        return yaml.safe_load(f)


def get_test_selections() -> Iterable[str]:
    project_root = get_project_root()
    workflow_path = project_root / WORKFLOW_PATH
    workflow = read_workflow(workflow_path)
    selections = \
        workflow['jobs']['scala_unit_tests']['strategy']['matrix']['tests']

    remainder_found = False

    for test_sel in selections:
        if test_sel == REMAINDER:
            if not remainder_found:
                remainder_found = True
                continue
            raise Exception(f"Two {REMAINDER} test selections found")
        yield test_sel
