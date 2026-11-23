# Makes sample.tar.gz for TarGzTest, by Python's own tarfile in the PAX format python-build-standalone's archives use:
# a folder, a file in it, an executable, a symbolic link to it, and a name longer than tar's 100 bytes.
import io
import os
import sys
import tarfile

here = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))
with tarfile.open(os.path.join(here, 'sample.tar.gz'), 'w:gz', format=tarfile.PAX_FORMAT) as tar:
    def add(name, data, mode=0o644):
        info = tarfile.TarInfo(name)
        info.size = len(data)
        info.mode = mode
        info.mtime = 0
        tar.addfile(info, io.BytesIO(data))
    folder = tarfile.TarInfo('python/bin')
    folder.type = tarfile.DIRTYPE
    folder.mode = 0o755
    tar.addfile(folder)
    add('python/lib/readme.txt', b'hello')
    add('python/bin/python3.10', b'#!/bin/sh\necho python\n', 0o755)
    link = tarfile.TarInfo('python/bin/python3')
    link.type = tarfile.SYMTYPE
    link.linkname = 'python3.10'
    tar.addfile(link)
    add('python/lib/' + 'a' * 120 + '.py', b'long')
