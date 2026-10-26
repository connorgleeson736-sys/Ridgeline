"""Aggregate immutable original-source quality evidence, never notebook claims."""
import argparse
from collections import Counter
import json
from pathlib import Path
from .state import State, atomic_json, key


def main():
    p=argparse.ArgumentParser();p.add_argument('--root',required=True);args=p.parse_args()
    root=Path(args.root);state=State(root,json.loads((root/'config.json').read_text()))
    audits=[json.loads(p.read_text()) for p in sorted((root/'data/raw').glob('*.audit.json'))]
    classes=Counter()
    for a in audits:classes.update(a['classification'])
    report=dict(schema=1,raw_files=len(audits),raw_points=sum(a['crop_points'] for a in audits),
                classes=dict(classes),withheld=sum(a['withheld'] for a in audits),
                nonfinite=sum(a['nonfinite'] for a in audits),crs='EPSG:2193 + EPSG:7839',
                source_encoding_precision_m=audits[0]['scales'] if audits else None,
                encoding_precision_is_not_survey_accuracy=True,
                sources=audits,overhangs_verified=False,
                notes=['All original point record fields preserved in cropped LAZ.',
                       'No inference of overhang coverage from top-down index coverage.',
                       'West Coast adjusted overlap is not superimposed on Canterbury delivery.'])
    path=root/'data'/'source-audit.json';atomic_json(path,report)
    signature=key(dict(stage='source-audit-v1',sources=[a['crop_sha256'] for a in audits]))
    if not state.valid('source-audit',signature):
        state.stage('source-audit',signature,[path],dict(points=report['raw_points'],files=report['raw_files']))
    print(json.dumps({k:v for k,v in report.items() if k!='sources'},indent=2))


if __name__=='__main__':main()
