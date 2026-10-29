plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace="nz.ridgeline.nativeapp"
 compileSdk=36
 defaultConfig { applicationId="nz.ridgeline.nativeapp"; minSdk=26; targetSdk=36; versionCode=1; versionName="0.1.0-prototype"; ndk {abiFilters+=providers.gradleProperty("ridgelineAbis").getOrElse("arm64-v8a").split(",")} }
 ndkVersion="29.0.14206865"
 compileOptions {sourceCompatibility=JavaVersion.VERSION_21;targetCompatibility=JavaVersion.VERSION_21}
 packaging {jniLibs {useLegacyPackaging=false}}
 testOptions {unitTests.all {it.systemProperty("ridgeline.assets",file("src/main/assets").absolutePath);it.systemProperty("ridgeline.fixtures",file("src/test/fixtures").absolutePath)}}
 androidResources {noCompress += "bin"}
}
kotlin {compilerOptions {jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)}}
dependencies { testImplementation("junit:junit:4.13.2"); testImplementation("org.json:json:20250517"); implementation("com.squareup.okhttp3:okhttp:4.12.0") }
