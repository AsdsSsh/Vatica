package com.example.vatica.config;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.vatica.secret.SecretCrypto;

/**
 * 用户自配模型槽位服务（迭代 13 I13-4）：
 * EPHEMERAL 槽位只存元数据，key 永不落库；ENCRYPTED_AT_REST 槽位 key 信封加密落库。
 */
@Service
public class UserModelService {

    private final UserModelSlotRepository slots;
    private final UserModelCredentialRepository credentials;
    private final SecretCrypto crypto;

    public UserModelService(UserModelSlotRepository slots, UserModelCredentialRepository credentials,
            SecretCrypto crypto) {
        this.slots = slots;
        this.credentials = credentials;
        this.crypto = crypto;
    }

    public record SaveRequest(String name, String protocol, String baseUrl, String model,
            Double temperature, boolean enabled, String credentialMode, String apiKey) {
    }

    public record View(String id, Long ownerId, String name, String protocol, String baseUrl,
            String model, Double temperature, boolean enabled, String credentialMode,
            boolean apiKeySet, String apiKeyHint) {
    }

    @Transactional(readOnly = true)
    public List<View> list(Long ownerId) {
        return slots.findByOwnerIdOrderByUpdatedAtDesc(ownerId).stream().map(this::view).toList();
    }

    @Transactional
    public View create(Long ownerId, SaveRequest request) {
        String mode = normalizeMode(request.credentialMode());
        UserModelSlot slot = new UserModelSlot(UUID.randomUUID().toString(), ownerId,
                trim(request.name()), normalizeProtocol(request.protocol()), trim(request.baseUrl()),
                trim(request.model()), request.temperature() == null ? 0.7 : request.temperature(),
                request.enabled(), mode);
        validate(slot);
        slots.save(slot);
        if (mode.equals(UserModelSlot.MODE_ENCRYPTED_AT_REST)) {
            storeKey(slot.getId(), request.apiKey());
        }
        return view(slot);
    }

    @Transactional
    public View update(Long ownerId, String id, SaveRequest request) {
        UserModelSlot slot = owned(ownerId, id);
        slot.setName(trim(request.name()));
        slot.setProtocol(normalizeProtocol(request.protocol()));
        slot.setBaseUrl(trim(request.baseUrl()));
        slot.setModel(trim(request.model()));
        slot.setTemperature(request.temperature() == null ? 0.7 : request.temperature());
        slot.setEnabled(request.enabled());
        slot.setCredentialMode(normalizeMode(request.credentialMode()));
        validate(slot);
        if (request.apiKey() != null) {
            if (slot.getCredentialMode().equals(UserModelSlot.MODE_ENCRYPTED_AT_REST)) {
                storeKey(slot.getId(), request.apiKey());
            } else {
                credentials.deleteById(slot.getId());
            }
        }
        return view(slots.save(slot));
    }

    @Transactional
    public View setMode(Long ownerId, String id, String mode, String apiKey) {
        UserModelSlot slot = owned(ownerId, id);
        String next = normalizeMode(mode);
        slot.setCredentialMode(next);
        if (next.equals(UserModelSlot.MODE_ENCRYPTED_AT_REST)) {
            if (apiKey != null && !apiKey.isBlank()) {
                storeKey(slot.getId(), apiKey);
            } else if (!credentials.existsById(slot.getId())) {
                throw new IllegalArgumentException("操作失败：开启云端保存时必须提供 API Key。");
            }
        } else {
            credentials.deleteById(slot.getId());
        }
        return view(slots.save(slot));
    }

    @Transactional
    public void delete(Long ownerId, String id) {
        owned(ownerId, id);
        credentials.deleteById(id);
        slots.deleteById(id);
    }

    /** 解析用户槽位（含解密后的 key）；供 ModelRegistry/请求路由使用。 */
    @Transactional(readOnly = true)
    public UserModelSlot resolveSlot(Long ownerId, String id) {
        UserModelSlot slot = owned(ownerId, id);
        if (!slot.isEnabled()) {
            throw new IllegalArgumentException("操作失败：模型未启用（" + slot.getName() + "）。");
        }
        return slot;
    }

    @Transactional(readOnly = true)
    public String resolveApiKey(Long ownerId, String id) {
        UserModelSlot slot = owned(ownerId, id);
        if (slot.getCredentialMode().equals(UserModelSlot.MODE_EPHEMERAL)) {
            return "";
        }
        return credentials.findById(id)
                .map(row -> crypto.decrypt(new SecretCrypto.EncryptedSecret(row.getWrappedDek(), row.getDekNonce(),
                        row.getCiphertext(), row.getDataNonce())))
                .orElse("");
    }

    private void storeKey(String slotId, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("操作失败：云端加密保存模式必须提供 API Key。");
        }
        SecretCrypto.EncryptedSecret secret = crypto.encrypt(apiKey);
        int nextVersion = credentials.findById(slotId).map(r -> r.getKeyVersion() + 1).orElse(1);
        String visible = apiKey.length() <= 4 ? apiKey : apiKey.substring(apiKey.length() - 4);
        credentials.save(new UserModelCredential(slotId, "…" + visible, secret.wrappedDek(), secret.dekNonce(),
                secret.ciphertext(), secret.dataNonce(), nextVersion));
    }

    private View view(UserModelSlot slot) {
        var credential = credentials.findById(slot.getId()).orElse(null);
        return new View(slot.getId(), slot.getOwnerId(), slot.getName(), slot.getProtocol(), slot.getBaseUrl(),
                slot.getModel(), slot.getTemperature(), slot.isEnabled(), slot.getCredentialMode(),
                credential != null, credential == null ? null : credential.getHint());
    }

    private UserModelSlot owned(Long ownerId, String id) {
        UserModelSlot slot = slots.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("操作失败：模型槽位不存在（" + id + "）。"));
        if (!slot.getOwnerId().equals(ownerId)) {
            throw new IllegalArgumentException("操作失败：无权访问该模型槽位。");
        }
        return slot;
    }

    private static void validate(UserModelSlot slot) {
        if (slot.getName().isBlank() || slot.getBaseUrl().isBlank() || slot.getModel().isBlank()) {
            throw new IllegalArgumentException("操作失败：名称 / Base URL / 模型 ID 不能为空。");
        }
        if (!slot.getProtocol().equals(ModelSlot.PROTOCOL_OPENAI)
                && !slot.getProtocol().equals(ModelSlot.PROTOCOL_ANTHROPIC)) {
            throw new IllegalArgumentException("操作失败：不支持的协议（" + slot.getProtocol() + "）。");
        }
        if (slot.getTemperature() < 0 || slot.getTemperature() > 2) {
            throw new IllegalArgumentException("操作失败：温度必须在 0-2 之间。");
        }
    }

    private static String normalizeMode(String mode) {
        String value = mode == null || mode.isBlank() ? UserModelSlot.MODE_EPHEMERAL : mode.trim().toUpperCase(Locale.ROOT);
        if (!value.equals(UserModelSlot.MODE_EPHEMERAL) && !value.equals(UserModelSlot.MODE_ENCRYPTED_AT_REST)) {
            throw new IllegalArgumentException("操作失败：credentialMode 仅支持 EPHEMERAL / ENCRYPTED_AT_REST。");
        }
        return value;
    }

    private static String normalizeProtocol(String protocol) {
        String value = protocol == null ? ModelSlot.PROTOCOL_OPENAI : protocol.trim().toLowerCase(Locale.ROOT);
        return value.isBlank() ? ModelSlot.PROTOCOL_OPENAI : value;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
