import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val sideBySide=providers.gradleProperty("ridgelineSideBySide").map(String::toBoolean).getOrElse(false)
// Optional identity for a separately signed checkpoint that installs alongside
// earlier builds. Defaults keep the existing package names and labels.
val explicitId=providers.gradleProperty("ridgelineApplicationId").orNull
val explicitLabel=providers.gradleProperty("ridgelineAppLabel").orNull
android {
 namespace="nz.ridgeline.nativeapp"
 compileSdk=36
 defaultConfig { applicationId = explicitId ?: if (sideBySide) "nz.ridgeline.nativeapp.performance" else "nz.ridgeline.nativeapp"
  manifestPlaceholders["ridgelineAppLabel"] = explicitLabel ?: if (sideBySide) "Ridgeline Native Performance" else "Ridgeline Native Prototype"
  minSdk=26; targetSdk=36; versionCode = 31; versionName="0.1.30-overlays-fixed"; ndk {abiFilters+=providers.gradleProperty("ridgelineAbis").getOrElse("arm64-v8a").split(",")} }
 // An optional external key makes signing reproducible without committing keys.
 signingConfigs.getByName("debug") {
  providers.environmentVariable("RIDGELINE_SIGNING_KEYSTORE").orNull?.let {storeFile=file(it)}
 }
 // Device checkpoints: not debuggable, so ART fully optimizes app code and
 // CheckJNI stays off. Same sources, assets and native libraries as debug;
 // R8 stays off so JNI entry points and reflection are untouched.
 buildTypes {
  create("performance") {
   initWith(getByName("release"))
   isDebuggable=false; isJniDebuggable=false; isMinifyEnabled=false; isShrinkResources=false
   signingConfig=signingConfigs.getByName("debug")
   matchingFallbacks+=listOf("release")
  }
 }
 ndkVersion="29.0.14206865"
 compileOptions {sourceCompatibility=JavaVersion.VERSION_21;targetCompatibility=JavaVersion.VERSION_21}
 packaging {jniLibs {useLegacyPackaging=false}}
 testOptions {unitTests.all {it.systemProperty("ridgeline.assets",file("src/main/assets").absolutePath);it.systemProperty("ridgeline.fixtures",file("src/test/fixtures").absolutePath)}}
 androidResources {noCompress += listOf("bin","zip")}
}
kotlin {compilerOptions {jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)}}
dependencies { testImplementation("junit:junit:4.13.2"); testImplementation("org.json:json:20250517"); testImplementation("com.squareup.okhttp3:mockwebserver3:5.3.2"); implementation("com.squareup.okhttp3:okhttp:5.3.2") }

// Optional managed-environment trust for local debug tests only. No CA is
// committed, and normal builds retain system trust without a session anchor.
val sessionCaPath=providers.environmentVariable("RIDGELINE_DEBUG_CA_PEM").orNull
val debugTrust=layout.buildDirectory.dir("generated/ridgelineDebugTrust/res")
val generateDebugTrust=tasks.register("generateDebugSessionTrust") {
    inputs.property("sessionCaEnabled",sessionCaPath!=null)
    if(sessionCaPath!=null)inputs.file(sessionCaPath)
    outputs.dir(debugTrust)
    doLast {
        val res=debugTrust.get().asFile
        val xml=res.resolve("xml/ridgeline_debug_network.xml");xml.parentFile.mkdirs()
        val anchor=res.resolve("raw/ridgeline_session_ca.crt")
        val domains=if(sessionCaPath!=null) {
            val ca=file(sessionCaPath)
            val certificate=ca.inputStream().use {CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate}
            certificate.checkValidity();require(certificate.basicConstraints>=0){"Debug trust needs a CA certificate"}
            anchor.parentFile.mkdirs();ca.copyTo(anchor,overwrite=true)
            """<domain-config cleartextTrafficPermitted="false">
                <domain includeSubdomains="false">basemaps.linz.govt.nz</domain>
                <domain includeSubdomains="false">services.arcgisonline.com</domain>
                <domain includeSubdomains="false">s3.amazonaws.com</domain>
                <trust-anchors><certificates src="system"/><certificates src="@raw/ridgeline_session_ca"/></trust-anchors>
            </domain-config>"""
        } else {anchor.delete();""}
        xml.writeText("""<network-security-config><base-config cleartextTrafficPermitted="false"><trust-anchors><certificates src="system"/></trust-anchors></base-config>$domains</network-security-config>""")
    }
}
android.sourceSets.getByName("debug").res.srcDir(debugTrust)
tasks.configureEach {if(name=="preDebugBuild")dependsOn(generateDebugTrust)}
