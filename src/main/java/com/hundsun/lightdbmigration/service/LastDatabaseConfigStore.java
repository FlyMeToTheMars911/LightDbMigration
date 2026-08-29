package com.hundsun.lightdbmigration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hundsun.lightdbmigration.model.DatabaseConfig;
import com.hundsun.lightdbmigration.model.LastDatabaseConfig;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

@Service
public class LastDatabaseConfigStore {

    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final int KEY_SIZE_BITS = 128;
    private static final int GCM_TAG_SIZE_BITS = 128;
    private static final int IV_SIZE_BYTES = 12;
    private static final int MINIMUM_ENCRYPTED_PAYLOAD_BYTES = 1;
    private static final byte[] FILE_MAGIC = new byte[] {'L', 'D', 'M', '1'};
    private static final String KEY_FILE_NAME = "last-config.key";
    private static final String CONFIG_FILE_NAME = "last-database-config.dat";
    private static final String TEMP_FILE_SUFFIX = ".tmp";

    private final ObjectMapper objectMapper;
    private final Path storageDirectory;
    private final Path keyFile;
    private final Path configFile;
    private final SecureRandom secureRandom = new SecureRandom();

    public LastDatabaseConfigStore(ObjectMapper objectMapper,
        @Value("${lightdb.config-store.directory:data}") String directory) {
        this.objectMapper = objectMapper;
        this.storageDirectory = Paths.get(directory).toAbsolutePath().normalize();
        this.keyFile = storageDirectory.resolve(KEY_FILE_NAME);
        this.configFile = storageDirectory.resolve(CONFIG_FILE_NAME);
    }

    public synchronized void save(DatabaseConfig source, DatabaseConfig target)
        throws IOException, GeneralSecurityException {
        if (source == null || target == null) {
            throw new IllegalArgumentException("源库和目标库配置不能为空");
        }
        Files.createDirectories(storageDirectory);
        SecretKey key = loadOrCreateKey();
        byte[] iv = new byte[IV_SIZE_BYTES];
        secureRandom.nextBytes(iv);
        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_SIZE_BITS, iv));
        cipher.updateAAD(FILE_MAGIC);
        byte[] plainText = objectMapper.writeValueAsBytes(new LastDatabaseConfig(source, target));
        try {
            byte[] cipherText = cipher.doFinal(plainText);
            byte[] fileContent = new byte[FILE_MAGIC.length + iv.length + cipherText.length];
            System.arraycopy(FILE_MAGIC, 0, fileContent, 0, FILE_MAGIC.length);
            System.arraycopy(iv, 0, fileContent, FILE_MAGIC.length, iv.length);
            System.arraycopy(cipherText, 0, fileContent, FILE_MAGIC.length + iv.length, cipherText.length);
            writeAtomically(configFile, fileContent);
            restrictFilePermissions(configFile);
        } finally {
            Arrays.fill(plainText, (byte)0);
        }
    }

    public synchronized LastDatabaseConfig load() throws IOException, GeneralSecurityException {
        if (!Files.exists(configFile)) {
            return null;
        }
        if (!Files.exists(keyFile)) {
            throw new GeneralSecurityException("配置密钥文件不存在");
        }
        byte[] fileContent = Files.readAllBytes(configFile);
        int minimumLength = FILE_MAGIC.length + IV_SIZE_BYTES + MINIMUM_ENCRYPTED_PAYLOAD_BYTES;
        if (fileContent.length < minimumLength
            || !Arrays.equals(FILE_MAGIC, Arrays.copyOfRange(fileContent, 0, FILE_MAGIC.length))) {
            throw new GeneralSecurityException("配置文件格式不正确");
        }
        byte[] iv = Arrays.copyOfRange(fileContent, FILE_MAGIC.length, FILE_MAGIC.length + IV_SIZE_BYTES);
        byte[] cipherText = Arrays.copyOfRange(fileContent, FILE_MAGIC.length + IV_SIZE_BYTES,
            fileContent.length);
        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, readKey(), new GCMParameterSpec(GCM_TAG_SIZE_BITS, iv));
        cipher.updateAAD(FILE_MAGIC);
        byte[] plainText = cipher.doFinal(cipherText);
        try {
            return objectMapper.readValue(plainText, LastDatabaseConfig.class);
        } finally {
            Arrays.fill(plainText, (byte)0);
        }
    }

    public synchronized void clear() throws IOException {
        Files.deleteIfExists(configFile);
        Files.deleteIfExists(storageDirectory.resolve(CONFIG_FILE_NAME + TEMP_FILE_SUFFIX));
        Files.deleteIfExists(keyFile);
        Files.deleteIfExists(storageDirectory.resolve(KEY_FILE_NAME + TEMP_FILE_SUFFIX));
        try {
            Files.delete(storageDirectory);
        } catch (IOException ignored) {
            // data 目录内可能有其他运行期文件，此时保留目录。
        }
    }

    public String displayLocation() {
        return configFile.toString();
    }

    private SecretKey loadOrCreateKey() throws IOException, GeneralSecurityException {
        if (Files.exists(keyFile)) {
            return readKey();
        }
        KeyGenerator keyGenerator = KeyGenerator.getInstance(KEY_ALGORITHM);
        keyGenerator.init(KEY_SIZE_BITS, secureRandom);
        SecretKey key = keyGenerator.generateKey();
        byte[] keyBytes = key.getEncoded();
        try {
            writeAtomically(keyFile, keyBytes);
        } finally {
            Arrays.fill(keyBytes, (byte)0);
        }
        restrictFilePermissions(keyFile);
        return key;
    }

    private SecretKey readKey() throws IOException, GeneralSecurityException {
        byte[] keyBytes = Files.readAllBytes(keyFile);
        try {
            if (keyBytes.length * Byte.SIZE != KEY_SIZE_BITS) {
                throw new GeneralSecurityException("配置密钥长度不正确");
            }
            return new SecretKeySpec(keyBytes, KEY_ALGORITHM);
        } finally {
            Arrays.fill(keyBytes, (byte)0);
        }
    }

    private void writeAtomically(Path target, byte[] content) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName().toString() + TEMP_FILE_SUFFIX);
        try {
            Files.write(temporary, content);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            throw exception;
        }
    }

    private void restrictFilePermissions(Path file) {
        Set<PosixFilePermission> permissions = EnumSet.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);
        try {
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows 等非 POSIX 文件系统沿用目录 ACL。
        }
    }
}
