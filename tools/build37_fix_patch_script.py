from pathlib import Path

path = Path('tools/build37_ipv6_patch.py')
text = path.read_text(encoding='utf-8')
old_pattern = "r'    private fun copyAddressToClipboard\\(\\) \\{.*?\\n    \\}\\n\\n    private fun ',"
new_pattern = "r'    private fun copyAddressToClipboard\\(\\) \\{.*?\\n    \\}\\n\\n    override fun onStart\\(\\) \\{',"
if old_pattern not in text:
    raise SystemExit('Build37 fix: padrao copyAddress nao encontrado')
text = text.replace(old_pattern, new_pattern, 1)
old_repl = "    private fun ''',\n    'MainActivity copyAddress'"
new_repl = "    override fun onStart() {''',\n    'MainActivity copyAddress'"
if old_repl not in text:
    raise SystemExit('Build37 fix: replacement copyAddress nao encontrado')
text = text.replace(old_repl, new_repl, 1)
path.write_text(text, encoding='utf-8')
print('Build37 patch script corrigido.')
