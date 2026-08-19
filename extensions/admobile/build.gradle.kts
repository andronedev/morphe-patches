import com.android.build.api.dsl.ApplicationExtension

configure<ApplicationExtension> {
    defaultConfig {
        // AdMobile itself targets API 23 and up.
        minSdk = 23
    }
}
