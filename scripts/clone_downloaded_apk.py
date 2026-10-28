"""Isolate a decoded APK while retaining its original Java class namespace."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root = Path(sys.argv[1])
old = 'dev.pocket.agent'
new = 'dev.pocket.agent.quicktest'
android = '{http://schemas.android.com/apk/res/android}'
ET.register_namespace('android', 'http://schemas.android.com/apk/res/android')
p = root / 'AndroidManifest.xml'
tree = ET.parse(p)
manifest = tree.getroot()
assert manifest.attrib['package'] == old
# Android resolves relative class names against the manifest package, so make
# every component explicit before changing that package.
for node in manifest.iter():
    for key in ('name', 'targetActivity', 'backupAgent', 'appComponentFactory', 'manageSpaceActivity'):
        attr = android + key
        value = node.get(attr)
        if value and (value.startswith('.') or ('.' not in value and node.tag in ('application', 'activity', 'service', 'receiver', 'provider', 'activity-alias'))):
            node.set(attr, old + (value if value.startswith('.') else '.' + value))
    for attr, value in list(node.attrib.items()):
        if value.startswith(old + '.') and (attr.endswith('authorities') or 'permission' in node.tag or attr in (android + 'permission', android + 'readPermission', android + 'writePermission')):
            node.set(attr, new + value[len(old):])
    if node.tag == 'application':
        node.set(android + 'label', 'Pocket Agent Quick Test')
    elif android + 'label' in node.attrib and node.get(android + 'label', '').startswith('Pocket Agent'):
        node.set(android + 'label', node.attrib[android + 'label'].replace('Pocket Agent', 'Pocket Agent Quick Test', 1))
manifest.set('package', new)
tree.write(p, encoding='utf-8', xml_declaration=True)
# Keep class descriptors intact; patch only exact self-package and provider
# strings, including the inlined BuildConfig.APPLICATION_ID constant.
count = 0
for p in root.glob('smali*/**/*.smali'):
    source = p.read_text()
    updated = source
    for suffix in ('', '.shizuku', '.accessibility-bridge', '.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'):
        updated = updated.replace('"' + old + suffix + '"', '"' + new + suffix + '"')
    if updated != source:
        p.write_text(updated)
        count += 1
print(f'Isolated manifest and self-package strings in {count} dex classes')
