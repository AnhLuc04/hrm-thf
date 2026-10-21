package com.example.hrm.push;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * Mã hoá WEB PUSH chỉ bằng thư viện có sẵn của Java (không cần thêm thư viện ngoài):
 *   - RFC 8291 (Message Encryption for Web Push, nội dung "aes128gcm" theo RFC 8188)
 *   - RFC 8292 (VAPID: khoá máy chủ, JWT ký ES256)
 */
public final class WebPushCrypto {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64URL_DEC = Base64.getUrlDecoder();

    private WebPushCrypto() {
    }

    // ------------------------------------------------------------------ khoá EC P-256

    public static KeyPair generateKeyPair() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
        return g.generateKeyPair();
    }

    private static ECParameterSpec p256() throws GeneralSecurityException {
        return ((ECPublicKey) generateKeyPair().getPublic()).getParams();
    }

    /** 65 byte 0x04 || X || Y */
    public static byte[] rawPublic(PublicKey key) {
        ECPoint w = ((ECPublicKey) key).getW();
        byte[] out = new byte[65];
        out[0] = 4;
        copy32(w.getAffineX(), out, 1);
        copy32(w.getAffineY(), out, 33);
        return out;
    }

    private static void copy32(BigInteger v, byte[] dst, int off) {
        byte[] b = v.toByteArray();
        if (b.length > 32) {
            System.arraycopy(b, b.length - 32, dst, off, 32);
        } else {
            System.arraycopy(b, 0, dst, off + 32 - b.length, b.length);
        }
    }

    public static PublicKey publicFromRaw(byte[] raw) throws GeneralSecurityException {
        if (raw.length != 65 || raw[0] != 4) {
            throw new GeneralSecurityException("Khoá p256dh không hợp lệ");
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(raw, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(raw, 33, 65));
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), p256()));
    }

    public static PrivateKey privateFromPkcs8(byte[] der) throws GeneralSecurityException {
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    public static String b64url(byte[] b) {
        return B64URL.encodeToString(b);
    }

    public static byte[] unb64url(String s) {
        String t = s.trim().replace('+', '-').replace('/', '_');
        while (t.endsWith("=")) {
            t = t.substring(0, t.length() - 1);
        }
        return B64URL_DEC.decode(t);
    }

    // ------------------------------------------------------------------ RFC 8291 aes128gcm

    /**
     * Mã hoá nội dung cho 1 thiết bị.
     * @param uaPublic khoá p256dh của trình duyệt (65 byte)
     * @param authSecret khoá auth của trình duyệt (16 byte)
     * @return thân HTTP gửi tới endpoint (Content-Encoding: aes128gcm)
     */
    public static byte[] encrypt(byte[] payload, byte[] uaPublic, byte[] authSecret) throws GeneralSecurityException {
        KeyPair as = generateKeyPair();
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return encrypt(payload, uaPublic, authSecret, as, salt);
    }

    /** Bản có khoá tạm + salt truyền vào (để kiểm thử) */
    public static byte[] encrypt(byte[] payload, byte[] uaPublic, byte[] authSecret, KeyPair as, byte[] salt)
            throws GeneralSecurityException {
        byte[] asPublic = rawPublic(as.getPublic());

        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(as.getPrivate());
        ka.doPhase(publicFromRaw(uaPublic), true);
        byte[] ecdhSecret = ka.generateSecret();

        // IKM = HKDF(auth, ecdh, "WebPush: info" 0x00 ua_public as_public, 32)
        byte[] prkKey = hmac(authSecret, ecdhSecret);
        byte[] keyInfo = concat("WebPush: info".getBytes(StandardCharsets.US_ASCII), new byte[]{0}, uaPublic, asPublic, new byte[]{1});
        byte[] ikm = hmac(prkKey, keyInfo);

        byte[] prk = hmac(salt, ikm);
        byte[] cek = Arrays.copyOf(hmac(prk, concat("Content-Encoding: aes128gcm".getBytes(StandardCharsets.US_ASCII), new byte[]{0, 1})), 16);
        byte[] nonce = Arrays.copyOf(hmac(prk, concat("Content-Encoding: nonce".getBytes(StandardCharsets.US_ASCII), new byte[]{0, 1})), 12);

        byte[] plain = concat(payload, new byte[]{2});   // 1 bản ghi duy nhất, dấu kết thúc 0x02, không đệm
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] cipher = c.doFinal(plain);

        ByteBuffer header = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length);
        header.put(salt).putInt(4096).put((byte) asPublic.length).put(asPublic);
        return concat(header.array(), cipher);
    }

    /** Giải mã (phía trình duyệt) - chỉ dùng để kiểm thử */
    public static byte[] decrypt(byte[] body, KeyPair ua, byte[] authSecret) throws GeneralSecurityException {
        byte[] salt = Arrays.copyOfRange(body, 0, 16);
        int idlen = body[20] & 0xFF;
        byte[] asPublic = Arrays.copyOfRange(body, 21, 21 + idlen);
        byte[] cipher = Arrays.copyOfRange(body, 21 + idlen, body.length);
        byte[] uaPublic = rawPublic(ua.getPublic());
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(ua.getPrivate());
        ka.doPhase(publicFromRaw(asPublic), true);
        byte[] ecdh = ka.generateSecret();
        byte[] ikm = hmac(hmac(authSecret, ecdh),
                concat("WebPush: info".getBytes(StandardCharsets.US_ASCII), new byte[]{0}, uaPublic, asPublic, new byte[]{1}));
        byte[] prk = hmac(salt, ikm);
        byte[] cek = Arrays.copyOf(hmac(prk, concat("Content-Encoding: aes128gcm".getBytes(StandardCharsets.US_ASCII), new byte[]{0, 1})), 16);
        byte[] nonce = Arrays.copyOf(hmac(prk, concat("Content-Encoding: nonce".getBytes(StandardCharsets.US_ASCII), new byte[]{0, 1})), 12);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] plain = c.doFinal(cipher);
        int end = plain.length - 1;
        while (end >= 0 && plain[end] == 0) {
            end--;
        }
        return Arrays.copyOf(plain, end);   // bỏ dấu 0x02
    }

    // ------------------------------------------------------------------ RFC 8292 VAPID

    /** Header Authorization: "vapid t=JWT, k=khoá công khai máy chủ" */
    public static String vapidAuthorization(String endpoint, String subject, PrivateKey serverPrivate, byte[] serverPublicRaw,
                                            long nowEpochSeconds) throws GeneralSecurityException {
        java.net.URI u = java.net.URI.create(endpoint);
        String aud = u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        String header = b64url("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = b64url(("{\"aud\":\"" + aud + "\",\"exp\":" + (nowEpochSeconds + 12 * 3600)
                + ",\"sub\":\"" + subject.replace("\"", "") + "\"}").getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + claims;
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(serverPrivate);
        s.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        byte[] jose = derToJose(s.sign());
        return "vapid t=" + signingInput + "." + b64url(jose) + ", k=" + b64url(serverPublicRaw);
    }

    /** Chữ ký DER (Java) -> 64 byte R || S (JWT ES256) */
    static byte[] derToJose(byte[] der) throws GeneralSecurityException {
        int i = 2;
        if ((der[1] & 0x80) != 0) {
            i = 2 + (der[1] & 0x7F);
        }
        if (der[i] != 0x02) throw new GeneralSecurityException("Chữ ký DER lỗi");
        int rLen = der[i + 1];
        byte[] r = Arrays.copyOfRange(der, i + 2, i + 2 + rLen);
        i = i + 2 + rLen;
        if (der[i] != 0x02) throw new GeneralSecurityException("Chữ ký DER lỗi");
        int sLen = der[i + 1];
        byte[] s = Arrays.copyOfRange(der, i + 2, i + 2 + sLen);
        byte[] out = new byte[64];
        copy32(new BigInteger(1, r), out, 0);
        copy32(new BigInteger(1, s), out, 32);
        return out;
    }

    // ------------------------------------------------------------------ tiện ích

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        return m.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            o.writeBytes(p);
        }
        return o.toByteArray();
    }
}
