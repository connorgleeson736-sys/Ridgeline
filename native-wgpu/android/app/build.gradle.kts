import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace="nz.ridgeline.nativeapp"
 compileSdk=36
 defaultConfig { applicationId="nz.ridgeline.nativeapp"; minSdk=26; targetSdk=36; versionCode=2; versionName="0.1.1-performance-checkpoint"; ndk {abiFilters+=providers.gradleProperty("ridgelineAbis").getOrElse("arm64-v8a").split(",")} }
 ndkVersion="29.0.14206865"
 compileOptions {sourceCompatibility=JavaVersion.VERSION_21;targetCompatibility=JavaVersion.VERSION_21}
 packaging {jniLibs {useLegacyPackaging=false}}
 testOptions {unitTests.all {it.systemProperty("ridgeline.assets",file("src/main/assets").absolutePath);it.systemProperty("ridgeline.fixtures",file("src/test/fixtures").absolutePath)}}
 androidResources {noCompress += listOf("bin","zip")}
}
kotlin {compilerOptions {jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)}}
dependencies { testImplementation("junit:junit:4.13.2"); testImplementation("org.json:json:20250517"); implementation("com.squareup.okhttp3:okhttp:4.12.0") }

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
