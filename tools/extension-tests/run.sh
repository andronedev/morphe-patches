#!/bin/sh
# Runs the AdMobile extension's tests on the JVM, against the Android stubs in stubs/.
#
# The extension is plain Java over a handful of Android APIs, so the parts worth testing (what is
# stored, what is answered to the app's reads, and when a sign in is finished) need no device and no
# Android SDK. Only javac.
set -e

here=$(dirname "$0")
extension="$here/../../extensions/admobile/src/main/java/app/morphe/extension/admobile"
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT

javac -nowarn -d "$out" \
    $(find "$here/stubs" -name '*.java' -not -path '*extension/admobile*') \
    "$extension/Credentials.java" \
    "$extension/OAuthFlow.java" \
    "$here/stubs/app/morphe/extension/admobile/CredentialsActivity.java" \
    "$here/FakeContext.java" \
    "$here/CredentialsTest.java" \
    "$here/OAuthFlowTest.java"

echo "Credentials"
java -cp "$out" CredentialsTest
echo
echo "OAuthFlow"
java -cp "$out" OAuthFlowTest
