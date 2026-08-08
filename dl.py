import urllib.request, re, os
html = open('trending.html', 'r', encoding='utf-8').read()
js_files = re.findall(r'src=\"([^\"]+\.js)\"', html)
for js in js_files:
    if js.startswith('/'): url = 'https://hanime.tv' + js
    else: url = js
    fname = url.split('/')[-1]
    if '?' in fname: fname = fname.split('?')[0]
    try:
        urllib.request.urlretrieve(url, fname)
        print('Downloaded', fname)
    except Exception as e:
        print('Failed', fname, e)
