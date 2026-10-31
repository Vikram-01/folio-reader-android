import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
a = [i for i in z.infolist() if i.filename.startswith('assets/')]
print('assets stored', sum(1 for i in a if i.compress_type == 0), 'deflated', sum(1 for i in a if i.compress_type != 0),
      'uncompressed bytes', sum(i.file_size for i in a))
for i in z.infolist():
    if i.filename.startswith('lib/'):
        print(i.filename, 'stored' if i.compress_type == 0 else 'deflated', i.file_size)
