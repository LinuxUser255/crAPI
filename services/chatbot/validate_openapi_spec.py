#!/usr/bin/env python3
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#         http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Guard against hand-edits of crapi-openapi-spec.json that would crash the
chatbot.

The chatbot's MCP server builds its tool set with FastMCP.from_openapi(). FastMCP
validates the spec strictly against the OpenAPI 3.0 models, where every Response
Object must carry a ``description`` (unless it is a ``$ref``). A missing one used
to crash the MCP server at startup, taking the whole chatbot down.

This runs in CI (standard library only) and fails if any operation response lacks
both ``description`` and ``$ref``. Exit 0 when the spec is fine, 1 otherwise.
"""

import json
import os
import sys

HTTP_METHODS = {"get", "put", "post", "delete", "options", "head", "patch", "trace"}
SPEC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "src", "resources", "crapi-openapi-spec.json")


def main():
    try:
        with open(SPEC, encoding="utf-8") as handle:
            spec = json.load(handle)
    except (OSError, ValueError) as error:
        print(f"Could not read {SPEC}: {error}")
        return 1

    problems = []
    for path, item in spec.get("paths", {}).items():
        if not isinstance(item, dict):
            continue
        for method, operation in item.items():
            if method.lower() not in HTTP_METHODS or not isinstance(operation, dict):
                continue
            for code, response in operation.get("responses", {}).items():
                if isinstance(response, dict) and "description" not in response and "$ref" not in response:
                    problems.append(f"{method.upper()} {path} -> response {code}")

    if problems:
        print("Invalid OpenAPI spec: response objects missing a required 'description':")
        for problem in problems:
            print(f"  - {problem}")
        return 1

    print("OpenAPI spec responses OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
