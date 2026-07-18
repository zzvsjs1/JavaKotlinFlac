# Third Party Notices

This project builds and bundles `FLAC.dll` from the official FLAC 1.5.0 source
archive, with libogg 1.3.6 statically linked for Ogg FLAC support. The wrapper
code is licensed separately under the project `LICENSE`.

The bundled FLAC library contains a small downstream patch maintained in
`native/patches`. It preserves a caller-supplied Vorbis comment vendor string
when the ordered metadata encoding API is used and updates the corresponding
public FLAC header documentation. Upstream libFLAC 1.5.0 normally replaces that
vendor string with its own encoder identifier.

The `examples` project uses Lanterna 3.1.5 for terminal widgets and JLine 4.3.1
for native system-terminal access. They are unmodified runtime dependencies and
are not part of the core `jflac` or `jflac-java-sound` API artefacts.

## Lanterna (examples only)

Copyright (C) 2010-2024 Martin Berglund

Lanterna is free software licensed under the GNU Lesser General Public License,
version 3 or (at your option) any later version. Its source code and licence
terms are available from <https://github.com/mabe02/lanterna> and
<https://www.gnu.org/licenses/lgpl-3.0.html>.

The Lanterna JAR is linked as a separate Gradle dependency. Recipients may
replace it with a compatible modified build under the terms of the LGPL.

## JLine (examples only)

Copyright (c) 2002-2026, the original author(s)

JLine is licensed under the BSD 3-Clause License. Its source code and licence
terms are available from <https://github.com/jline/jline3> and
<https://opensource.org/licenses/BSD-3-Clause>.

The examples use the separate `jline-terminal` and `jline-terminal-jni`
modules. The JNI module supplies native terminal access for supported systems;
recipients may replace these JARs in accordance with the BSD licence.

## FLAC

Copyright (C) 2000-2009 Josh Coalson
Copyright (C) 2011-2025 Xiph.Org Foundation

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

- Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

- Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.

- Neither the name of the Xiph.Org Foundation nor the names of its contributors
  may be used to endorse or promote products derived from this software without
  specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE FOUNDATION OR CONTRIBUTORS BE LIABLE FOR ANY
DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## libogg

Copyright (c) 2002, Xiph.org Foundation

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

- Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.

- Redistributions in binary form must reproduce the above copyright
notice, this list of conditions and the following disclaimer in the
documentation and/or other materials provided with the distribution.

- Neither the name of the Xiph.org Foundation nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE FOUNDATION
OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
