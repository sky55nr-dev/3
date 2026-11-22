package app.linklauncher.phone;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import com.android.apksig.ApkSigner;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

final class ApkFactory {
    private static final String KEY_ALIAS = "generated-link-apps";
    private static final String TEMPLATE_PACKAGE = "app.linklauncher.generated.placeholder";
    private static final String TEMPLATE_LABEL = "LinkLauncherTemplateLabel";

    static File create(Context context, String label, String address, byte[] icon) throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "");
        String packageName = "app.linklauncher.generated.l" + id;
        File directory = new File(context.getCacheDir(), "generated");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create APK directory");
        }
        File output = new File(directory, id + ".apk");
        File unsigned = File.createTempFile("unsigned-", ".apk", context.getCacheDir());
        try {
            try (InputStream template = context.getAssets().open("link-template.apk")) {
                specialize(template, unsigned, packageName, label, address, icon);
            }
            sign(unsigned, output);
            return output;
        } catch (Exception exception) {
            output.delete();
            throw exception;
        } finally {
            unsigned.delete();
        }
    }

    private static synchronized void sign(File input, File output) throws Exception {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        if (!keys.containsAlias(KEY_ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .build());
            generator.generateKeyPair();
        }
        PrivateKey key = (PrivateKey) keys.getKey(KEY_ALIAS, null);
        X509Certificate certificate = (X509Certificate) keys.getCertificate(KEY_ALIAS);
        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder("link", key,
                Collections.singletonList(certificate)).build();
        new ApkSigner.Builder(Collections.singletonList(signer))
                .setInputApk(input)
                .setOutputApk(output)
                .setMinSdkVersion(26)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(false)
                .setV4SigningEnabled(false)
                .setVerityEnabled(false)
                .build().sign();
    }

    static void specialize(InputStream template, File output, String packageName,
                           String label, String address, byte[] icon) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream source = new ZipInputStream(template)) {
            byte[] buffer = new byte[8192];
            ZipEntry entry;
            while ((entry = source.getNextEntry()) != null) {
                if (entry.isDirectory() || entry.getName().startsWith("META-INF/")) {
                    continue;
                }
                ByteArrayOutputStream contents = new ByteArrayOutputStream();
                int count;
                while ((count = source.read(buffer)) != -1) {
                    contents.write(buffer, 0, count);
                }
                entries.put(entry.getName(), contents.toByteArray());
            }
        }
        byte[] resources = entries.remove("resources.arsc");
        byte[] manifest = entries.get("AndroidManifest.xml");
        if (resources == null || manifest == null) {
            throw new IOException("Missing template resources");
        }
        entries.put("AndroidManifest.xml", rewriteManifest(manifest, packageName, label));
        if (icon != null) {
            String iconPath = null;
            for (String path : entries.keySet()) {
                if (path.startsWith("res/") && path.endsWith("/link_icon.png")) {
                    iconPath = path;
                    break;
                }
            }
            if (iconPath == null) {
                throw new IOException("Missing template icon");
            }
            entries.put(iconPath, icon);
        }
        entries.put("assets/address.txt", address.getBytes(StandardCharsets.UTF_8));
        try (ZipOutputStream target = new ZipOutputStream(new FileOutputStream(output))) {
            writeResourceTable(target, renameResourcePackage(resources, packageName));
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                target.putNextEntry(new ZipEntry(entry.getKey()));
                target.write(entry.getValue());
                target.closeEntry();
            }
        }
    }

    private static void writeResourceTable(ZipOutputStream target, byte[] resources) throws IOException {
        // The first ZIP entry has an aligned data offset: 30 header bytes plus 14 name bytes.
        ZipEntry entry = new ZipEntry("resources.arsc");
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(resources.length);
        entry.setCompressedSize(resources.length);
        entry.setTime(946684800000L);
        CRC32 crc = new CRC32();
        crc.update(resources);
        entry.setCrc(crc.getValue());
        target.putNextEntry(entry);
        target.write(resources);
        target.closeEntry();
    }

    private static byte[] renameResourcePackage(byte[] resources, String packageName) throws IOException {
        ByteBuffer table = ByteBuffer.wrap(resources).order(ByteOrder.LITTLE_ENDIAN);
        if (table.getShort(0) != 2 || table.getInt(8) != 1 || packageName.length() >= 128) {
            throw new IOException("Unexpected template resource table");
        }
        for (int offset = table.getShort(2) & 0xffff; offset <= resources.length - 8; ) {
            int chunkSize = table.getInt(offset + 4);
            if (chunkSize < 8 || chunkSize > resources.length - offset) {
                break;
            }
            if (table.getShort(offset) == 0x0200 && chunkSize >= 268) {
                int nameOffset = offset + 12;
                for (int index = 0; index < 128; index++) {
                    char character = index < packageName.length() ? packageName.charAt(index) : 0;
                    table.putChar(nameOffset + index * 2, character);
                }
                return resources;
            }
            offset += chunkSize;
        }
        throw new IOException("Missing template resource package");
    }

    // Binary Android XML stores references to strings by index. Preserve those indices
    // and rebuild the unstyled template's string pool with the two replacement values.
    private static byte[] rewriteManifest(byte[] manifest, String packageName, String label)
            throws IOException {
        ByteBuffer original = ByteBuffer.wrap(manifest).order(ByteOrder.LITTLE_ENDIAN);
        int pool = original.getShort(2) & 0xffff;
        int header = original.getShort(pool + 2) & 0xffff;
        int poolSize = original.getInt(pool + 4);
        int count = original.getInt(pool + 8);
        int flags = original.getInt(pool + 16);
        int strings = original.getInt(pool + 20);
        if (original.getShort(pool) != 1 || original.getInt(pool + 12) != 0) {
            throw new IOException("Unexpected manifest string pool");
        }
        boolean utf8 = (flags & 0x100) != 0;
        ByteArrayOutputStream contents = new ByteArrayOutputStream();
        int[] offsets = new int[count];
        boolean packageFound = false;
        boolean labelFound = false;
        for (int index = 0; index < count; index++) {
            offsets[index] = contents.size();
            original.position(pool + strings + original.getInt(pool + header + index * 4));
            int start = original.position();
            int charCount = readLength(original, utf8);
            int byteCount = utf8 ? readLength(original, true) : charCount * 2;
            String value = new String(manifest, original.position(), byteCount,
                    utf8 ? StandardCharsets.UTF_8 : StandardCharsets.UTF_16LE);
            if (TEMPLATE_PACKAGE.equals(value)) {
                writeString(contents, packageName, utf8);
                packageFound = true;
            } else if (TEMPLATE_LABEL.equals(value)) {
                writeString(contents, label, utf8);
                labelFound = true;
            } else {
                contents.write(manifest, start, original.position() - start + byteCount + (utf8 ? 1 : 2));
            }
        }
        if (!packageFound || !labelFound) {
            throw new IOException("Missing template manifest values");
        }
        while ((contents.size() & 3) != 0) {
            contents.write(0);
        }
        int newPoolSize = strings + contents.size();
        ByteBuffer updated = ByteBuffer.allocate(manifest.length - poolSize + newPoolSize)
                .order(ByteOrder.LITTLE_ENDIAN);
        updated.put(manifest, 0, pool + strings);
        updated.putInt(4, updated.capacity());
        updated.putInt(pool + 4, newPoolSize);
        updated.putInt(pool + 16, flags & ~1);
        for (int index = 0; index < count; index++) {
            updated.putInt(pool + header + index * 4, offsets[index]);
        }
        updated.put(contents.toByteArray());
        updated.put(manifest, pool + poolSize, manifest.length - pool - poolSize);
        return updated.array();
    }

    private static int readLength(ByteBuffer buffer, boolean utf8) {
        if (utf8) {
            int first = buffer.get() & 0xff;
            return first < 128 ? first : ((first & 127) << 8) | (buffer.get() & 0xff);
        }
        int first = buffer.getShort() & 0xffff;
        return first < 32768 ? first : ((first & 32767) << 16) | (buffer.getShort() & 0xffff);
    }

    private static void writeString(ByteArrayOutputStream output, String value, boolean utf8) {
        byte[] bytes = value.getBytes(utf8 ? StandardCharsets.UTF_8 : StandardCharsets.UTF_16LE);
        if (utf8) {
            output.write(value.length());
            if (bytes.length >= 128) {
                output.write(128 | (bytes.length >> 8));
            }
            output.write(bytes.length & 255);
        } else {
            output.write(value.length() & 255);
            output.write(value.length() >> 8);
        }
        output.write(bytes, 0, bytes.length);
        output.write(0);
        if (!utf8) {
            output.write(0);
        }
    }
}
