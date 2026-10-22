#!/bin/sh
set -eux

machine=$(uname -m)
if [ "${machine}" != "x86_64" ]; then
  echo "unsupported architecture: ${machine}; build with --platform=linux/amd64" >&2
  exit 1
fi

binaryen_tar="binaryen-${BINARYEN_VERSION}-x86_64-linux.tar.gz"
curl -fsSL -o "/tmp/${binaryen_tar}" \
  "https://github.com/WebAssembly/binaryen/releases/download/${BINARYEN_VERSION}/${binaryen_tar}"
echo "${BINARYEN_X86_64_SHA256}  /tmp/${binaryen_tar}" | sha256sum -c -
tar -xzf "/tmp/${binaryen_tar}" -C /opt
mv "/opt/binaryen-${BINARYEN_VERSION}" /opt/binaryen
rm "/tmp/${binaryen_tar}"
