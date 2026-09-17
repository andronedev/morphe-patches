# [2.4.0](https://github.com/andronedev/morphe-patches/compare/v2.3.0...v2.4.0) (2026-09-17)


### Bug Fixes

* **admobile:** exchange the code once the app is back in front ([b9885c3](https://github.com/andronedev/morphe-patches/commit/b9885c36e1a0839e5c991e020c266358c803a1cd))
* **admobile:** give the amounts a currency symbol, and say pro is on ([86624f9](https://github.com/andronedev/morphe-patches/commit/86624f9be8fbd05ad2ffd0731646ccaf6b559ca5))
* **admobile:** make the app's own sign out actually sign out ([49f9361](https://github.com/andronedev/morphe-patches/commit/49f9361ed476c3e7fc570d7713ecfc268419e867))
* **admobile:** refresh the access token before serving it ([8c404f9](https://github.com/andronedev/morphe-patches/commit/8c404f9ba6858d4ddd7bbf2d7d2de0ef381c3eea))
* **admobile:** serve the access token, not just the refresh token ([a291b28](https://github.com/andronedev/morphe-patches/commit/a291b2886422947102f617b57ae49c74dbbcb6c5))
* **admobile:** stop a failed account lookup from burning the consent ([07157fd](https://github.com/andronedev/morphe-patches/commit/07157fd50d58fdc79857e4b00301ac388ff0a312))
* **admobile:** stop the sign in form from restarting its own work ([b65b0dd](https://github.com/andronedev/morphe-patches/commit/b65b0dd1e4924aa7535e00eec6a33254f6836d3c))
* **admobile:** survive the coroutine resuming the store reads ([e2c691e](https://github.com/andronedev/morphe-patches/commit/e2c691e6eb812317eadda3148df58132e410660f))
* **admobile:** write credentials to disk before killing the process ([8e0f9b5](https://github.com/andronedev/morphe-patches/commit/8e0f9b51bf4675693fc2aec39aeee1b48a905e41))
* **admobile:** write the account into the database, not just answer for it ([43d470d](https://github.com/andronedev/morphe-patches/commit/43d470d9c6d5616581815f5bc617220601b8f5a1))
* **tools:** match the sign-in call across apktool line directives ([fed435c](https://github.com/andronedev/morphe-patches/commit/fed435c0b0e328c18a625b26d32bc71e1d8d1b39))


### Features

* **admobile:** add Hide Ads and Disable Ad Requests patches ([fd42e21](https://github.com/andronedev/morphe-patches/commit/fd42e21deffa881818ad34c52e768b53a0689619))
* **admobile:** add Pro Unlock patch ([8a11724](https://github.com/andronedev/morphe-patches/commit/8a11724c33bc39d24198b8463f16507b5cf50a0f))
* **admobile:** enter the AdMob credentials in the app ([2b01f44](https://github.com/andronedev/morphe-patches/commit/2b01f44d9a1845dfa68c7a45daa72a1bdfe139d2))
* **admobile:** explain where the OAuth client comes from, in the form ([bac5903](https://github.com/andronedev/morphe-patches/commit/bac5903ba3ce4537abadeff0d836296b9662b3ee))
* **admobile:** lay the form out as two steps, and allow disconnecting ([05213c7](https://github.com/andronedev/morphe-patches/commit/05213c77fbbe7efe9dd3c26e700169dc80f43c85))
* **admobile:** make an empty dashboard diagnosable ([a6c82f4](https://github.com/andronedev/morphe-patches/commit/a6c82f485aad8bc4738ec1ccdeef1668209797b0))
* **admobile:** open the credentials form from the sign in button ([5480615](https://github.com/andronedev/morphe-patches/commit/5480615d58d9181cf26a89c88a9c1f6aa226ba2d))
* **admobile:** open the credentials form from the sign in button ([ad84fab](https://github.com/andronedev/morphe-patches/commit/ad84fabb40eae798a6c0a8e6cd8136782a069008))
* **admobile:** paint the form from the app's Material 3 theme ([9cd0794](https://github.com/andronedev/morphe-patches/commit/9cd079410d7c794a69786be8e98323936c7f6cf2))
* **admobile:** run the Google consent inside the app ([f72fa75](https://github.com/andronedev/morphe-patches/commit/f72fa75531c5788a20953f67e5fde7a58faccec1))
* **admobile:** sign in with your own OAuth client ([08c6d1f](https://github.com/andronedev/morphe-patches/commit/08c6d1f77bb7dc178fc5f95829e363287b544b9e))
* **tools:** cover every AdMobile patch in the one script ([f0f2216](https://github.com/andronedev/morphe-patches/commit/f0f221635b86bb24b9e6496c788fbb6b84e6ff9d))

# [2.3.0](https://github.com/andronedev/morphe-patches/compare/v2.2.1...v2.3.0) (2026-04-01)


### Features

* Update Morphe dependencies and refactor Transit compatibility ([1613c6b](https://github.com/andronedev/morphe-patches/commit/1613c6b1e7b21be9b25e403ec2e45516115156ff))

# [2.3.0-dev.1](https://github.com/andronedev/morphe-patches/compare/v2.2.1...v2.3.0-dev.1) (2026-04-01)


### Features

* Update Morphe dependencies and refactor Transit compatibility ([1613c6b](https://github.com/andronedev/morphe-patches/commit/1613c6b1e7b21be9b25e403ec2e45516115156ff))

## [2.2.1](https://github.com/andronedev/morphe-patches/compare/v2.2.0...v2.2.1) (2026-04-01)


### Bug Fixes

* replace rawResourcePatch with native document() API for Maps key patching ([5d50f38](https://github.com/andronedev/morphe-patches/commit/5d50f3879a3a29d79c4a9c1809ffcd242cb31125))

## [2.2.1-dev.1](https://github.com/andronedev/morphe-patches/compare/v2.2.0...v2.2.1-dev.1) (2026-04-01)


### Bug Fixes

* replace rawResourcePatch with native document() API for Maps key patching ([5d50f38](https://github.com/andronedev/morphe-patches/commit/5d50f3879a3a29d79c4a9c1809ffcd242cb31125))

# [2.2.0](https://github.com/andronedev/morphe-patches/compare/v2.1.0...v2.2.0) (2026-02-12)


### Features

* Add Custom Maps API Key patch and update README with new patch details ([c2db592](https://github.com/andronedev/morphe-patches/commit/c2db5921014b9508fae2db7146d6d73b28f3fdd6))

# [2.2.0-dev.1](https://github.com/andronedev/morphe-patches/compare/v2.1.0...v2.2.0-dev.1) (2026-02-12)


### Features

* Add Custom Maps API Key patch and update README with new patch details ([c2db592](https://github.com/andronedev/morphe-patches/commit/c2db5921014b9508fae2db7146d6d73b28f3fdd6))

# [2.1.0](https://github.com/andronedev/morphe-patches/compare/v2.0.1...v2.1.0) (2026-02-11)


### Features

* **transit:** support v6.1.0 fingerprint and bump version to 2.1.0 ([dc7f31e](https://github.com/andronedev/morphe-patches/commit/dc7f31ea98dac04ceaf48dd5c219904e2f226089))

## [2.0.1](https://github.com/andronedev/revanced-patches/compare/v2.0.0...v2.0.1) (2026-02-11)
