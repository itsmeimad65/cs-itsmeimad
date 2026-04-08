dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

// Use an integer for version numbers
version = 1

cloudstream {
    description = "Watch Sidemen Side+ content for free"
    authors = listOf("itsmeimad")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 1

    tvTypes = listOf("TvSeries", "Movie")

    requiresResources = false
    language = "en"

    iconUrl = "https://www.free-sideplus.com/wp-content/uploads/2026/03/ontario-logo-dark-mode-2.webp"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}