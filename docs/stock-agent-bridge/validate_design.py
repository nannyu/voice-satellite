"""Validate the design bundle only; no network or device operations."""
from pathlib import Path
import json
import re
import sys


def main() -> int:
    root = Path(__file__).resolve().parent
    try:
        document = (root.parent / '08-stock-agent-bridge.md').read_text(encoding='utf-8')
        examples = json.loads((root / 'agent-api-examples.json').read_text(encoding='utf-8'))
        matrix = json.loads((root / 'acceptance-matrix.json').read_text(encoding='utf-8'))
        sources = json.loads((root / 'sources.json').read_text(encoding='utf-8'))
        checks = []

        fences = re.findall(r'^```.*$', document, flags=re.M)
        if len(fences) % 2:
            raise ValueError('Unbalanced fenced code blocks')
        blocks = [json.loads(s) for s in re.findall(r'```json\n(.*?)\n```', document, re.S)]
        if len(blocks) != 2:
            raise ValueError('Expected two JSON examples in 08-stock-agent-bridge.md')
        checks.append('Markdown fences and embedded JSON are valid')

        request = examples['create_turn']['body']
        result = examples['completed_response']['body']
        if blocks != [request, result]:
            raise ValueError('Document and separate API examples do not match')
        for field in ('request_id', 'turn_id', 'generation'):
            if request[field] != result[field]:
                raise ValueError(f'Mismatched request/result field: {field}')
        if examples['cancel_turn']['body']['target_generation'] != request['generation']:
            raise ValueError('Cancellation target generation mismatch')
        if examples['cancel_response_example']['body']['execution_stopped'] is not None:
            raise ValueError('Example should not assume cancellation success')
        checks.append('Request/result/cancel IDs and generation agree')

        ids = [case['id'] for case in matrix['cases']]
        if ids != [f'A{n:02d}' for n in range(1, 21)]:
            raise ValueError('Acceptance IDs must be unique and cover A01 through A20')
        if any(case['status'] != 'not_run' for case in matrix['cases']):
            raise ValueError('Design bundle must not claim device tests have run')
        checks.append('20 proposed acceptance cases are all explicitly not_run')

        known = {source['id'] for source in sources['sources']}
        used = set(re.findall(r'\[(S\d+)\]', document))
        if len(known) != 13 or not used <= known:
            raise ValueError('Source references are missing or inconsistent')
        if examples['document_status'] != 'design_only_not_implemented':
            raise ValueError('Missing implementation-status boundary')
        checks.append('13 source IDs resolve and implementation status is explicit')

        for entry in checks:
            print('PASS:', entry)
        print('Scope: document consistency only; no device/network/service tests were run.')
        return 0
    except (OSError, ValueError, KeyError, TypeError) as exc:
        print(f'FAIL: {exc}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
