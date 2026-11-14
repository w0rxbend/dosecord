package dosegram.infra.crypto

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM from the JDK with a random 96-bit nonce per value (ARCHITECTURE SEC-01). The output is the nonce
  * followed by the ciphertext and its 128-bit tag. Nothing here logs or keeps a key or a plaintext.
  */
private[crypto] object Aead:
  val NonceBytes: Int = 12
  private val TagBits = 128
  private val random = SecureRandom()

  def seal(key: Array[Byte], aad: Array[Byte], plaintext: Array[Byte]): Array[Byte] =
    val nonce = new Array[Byte](NonceBytes)
    random.nextBytes(nonce)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TagBits, nonce))
    cipher.updateAAD(aad)
    nonce ++ cipher.doFinal(plaintext)

  /** `None` when the value does not authenticate under `key` and `aad`. */
  def open(key: Array[Byte], aad: Array[Byte], value: Array[Byte]): Option[Array[Byte]] =
    if value.length < NonceBytes + TagBits / 8 then None
    else
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TagBits, value, 0, NonceBytes))
      cipher.updateAAD(aad)
      try Some(cipher.doFinal(value, NonceBytes, value.length - NonceBytes))
      catch case _: GeneralSecurityException => None

  def hmac(key: Array[Byte], message: Array[Byte]): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    mac.doFinal(message)

  def freshKey(): Array[Byte] =
    val key = new Array[Byte](Kek.Bytes)
    random.nextBytes(key)
    key
