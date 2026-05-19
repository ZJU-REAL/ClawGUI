package com.clawgui.ng.runtime.shizuku.wadb

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Security
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Persistent ADB key pair + self-signed certificate so subsequent wireless-debugging
 * connections survive app restarts. The phone's `/data/misc/adb/adb_keys`
 * remembers the public key once we've paired — as long as our private key
 * file is intact, no re-pairing is needed.
 *
 * Files (in `filesDir/wadb/`):
 *   adbkey       — PrivateKey (Java serialised)
 *   adbcert.pem  — X.509 self-signed certificate (PEM)
 *
 * Note: libadb-android 3.1.1 only requires a PrivateKey + Certificate — we
 * never need its internal `KeyPair` type (which is package-private).
 */
class AdbKeyStore(ctx: Context) {

    init {
        // Android ships its own stripped-down "BC" provider; `Security.addProvider`
        // is a silent no-op if the name is taken. Remove the stub first, then
        // install the full BouncyCastle 1.78.1 we shipped — this guarantees
        // SHA256WITHRSA et al. are reachable via getProvider("BC").
        runCatching { Security.removeProvider("BC") }
        Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
    }

    /** The provider instance we'll hand to JcaContentSignerBuilder directly, so the
     *  signer construction can't be derailed by Android's stub. */
    private val bcProvider: java.security.Provider =
        org.bouncycastle.jce.provider.BouncyCastleProvider()

    private val dir = File(ctx.filesDir, "wadb").apply { mkdirs() }
    private val keyFile = File(dir, "adbkey")
    private val certFile = File(dir, "adbcert.pem")

    @Synchronized
    fun ensure(): Pair<PrivateKey, Certificate> {
        if (keyFile.exists() && certFile.exists()) {
            runCatching { return load() }
                .onFailure { keyFile.delete(); certFile.delete() }
        }
        return generate()
    }

    val privateKey: PrivateKey get() = ensure().first
    val certificate: Certificate get() = ensure().second

    @Synchronized
    fun wipe() {
        keyFile.delete(); certFile.delete()
    }

    @Synchronized
    fun exists(): Boolean = keyFile.exists() && certFile.exists()

    private fun load(): Pair<PrivateKey, Certificate> {
        val key = ObjectInputStream(FileInputStream(keyFile)).use { it.readObject() as PrivateKey }
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(FileInputStream(certFile))
        return key to cert
    }

    private fun generate(): Pair<PrivateKey, Certificate> {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val kp: KeyPair = gen.generateKeyPair()

        val cert = selfSign(kp.private, kp.public)
        // Persist
        ObjectOutputStream(FileOutputStream(keyFile)).use { it.writeObject(kp.private) }
        FileOutputStream(certFile).use { out ->
            out.write("-----BEGIN CERTIFICATE-----\n".toByteArray())
            out.write(android.util.Base64.encode(cert.encoded, android.util.Base64.NO_WRAP))
            out.write("\n-----END CERTIFICATE-----\n".toByteArray())
        }
        return kp.private to cert
    }

    /** Build a long-lived self-signed certificate for the [public] key. */
    private fun selfSign(priv: PrivateKey, public: PublicKey): X509Certificate {
        val now = System.currentTimeMillis()
        val from = Date(now - TimeUnit.HOURS.toMillis(1))
        val to = Date(now + TimeUnit.DAYS.toMillis(365L * 25))
        val serial = BigInteger.valueOf(now)
        val owner = X500Name("CN=ClawGUI-NG, OU=ng, O=ZJU-REAL, L=Hangzhou, C=CN")
        val builder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
            owner, serial, from, to, owner, public,
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(bcProvider)
            .build(priv)
        val holder = builder.build(signer)
        return JcaX509CertificateConverter().setProvider(bcProvider).getCertificate(holder)
    }
}
