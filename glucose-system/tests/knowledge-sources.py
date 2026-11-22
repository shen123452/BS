"""Save public NIDDK source pages and readable text for local knowledge review."""
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
from urllib.request import Request, urlopen

from bs4 import BeautifulSoup

TESTS = Path(__file__).resolve().parent
OUTPUT = TESTS / 'fixtures' / 'knowledge-sources'
HTML_OUTPUT = TESTS / '.artifacts' / 'knowledge-acceptance-2026-10-25' / 'sources'
SOURCES = {
    'low-blood-glucose': 'https://www.niddk.nih.gov/health-information/diabetes/overview/preventing-problems/low-blood-glucose-hypoglycemia',
    'tests-diagnosis': 'https://www.niddk.nih.gov/health-information/diabetes/overview/tests-diagnosis',
    'managing-diabetes': 'https://www.niddk.nih.gov/health-information/diabetes/overview/managing-diabetes',
    'continuous-glucose-monitoring': 'https://www.niddk.nih.gov/health-information/diabetes/overview/managing-diabetes/continuous-glucose-monitoring',
    'low-blood-sugar-self-care': 'https://medlineplus.gov/ency/patientinstructions/000085.htm',
    'hypoglycaemia-first-aid': 'https://www.nhs.uk/conditions/low-blood-sugar-hypoglycaemia/',
}


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    HTML_OUTPUT.mkdir(parents=True, exist_ok=True)
    manifest = []
    for name, url in SOURCES.items():
        request = Request(url, headers={'User-Agent': 'GlucoseKnowledgeResearch/1.0'})
        with urlopen(request, timeout=25) as response:
            raw = response.read()
            status = response.status
            final_url = response.url
        soup = BeautifulSoup(raw, 'html.parser')
        title = soup.title.get_text(' ', strip=True)
        article = soup.select_one('main') or soup.select_one('article')
        if status != 200 or article is None:
            raise RuntimeError(f'No readable source: {url}')
        for element in article.select('script, style, nav, form'):
            element.decompose()
        source_text = '\n'.join(line.strip() for line in article.get_text('\n', strip=True).splitlines() if line.strip())
        (HTML_OUTPUT / f'{name}.html').write_bytes(raw)
        (OUTPUT / f'{name}.txt').write_bytes((source_text + '\n').encode('utf-8'))
        record = {
            'id': name, 'url': url, 'finalUrl': final_url, 'status': status, 'title': title,
            'retrievedAt': datetime.now(timezone.utc).isoformat(),
            'htmlSha256': hashlib.sha256(raw).hexdigest(),
            'textSha256': hashlib.sha256((source_text + '\n').encode('utf-8')).hexdigest(),
            'textCharacters': len(source_text),
        }
        manifest.append(record)
        print(json.dumps(record, ensure_ascii=True))
    (OUTPUT / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
