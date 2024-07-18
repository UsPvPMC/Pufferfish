package net.minecraft.network.chat;

import com.google.common.primitives.Ints;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.security.SignatureException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.SignatureUpdater;
import net.minecraft.util.SignatureValidator;

public record PlayerChatMessage(SignedMessageLink link, @Nullable MessageSignature signature, SignedMessageBody signedBody, @Nullable Component unsignedContent, FilterMask filterMask) {
    public static final MapCodec<PlayerChatMessage> MAP_CODEC = RecordCodecBuilder.mapCodec((instance) -> {
        return instance.group(SignedMessageLink.CODEC.fieldOf("link").forGetter(PlayerChatMessage::link), MessageSignature.CODEC.optionalFieldOf("signature").forGetter((message) -> {
            return Optional.ofNullable(message.signature);
        }), SignedMessageBody.MAP_CODEC.forGetter(PlayerChatMessage::signedBody), ExtraCodecs.COMPONENT.optionalFieldOf("unsigned_content").forGetter((message) -> {
            return Optional.ofNullable(message.unsignedContent);
        }), FilterMask.CODEC.optionalFieldOf("filter_mask", FilterMask.PASS_THROUGH).forGetter(PlayerChatMessage::filterMask)).apply(instance, (link, signature, signedBody, unsignedContent, filterMask) -> {
            return new PlayerChatMessage(link, signature.orElse((MessageSignature)null), signedBody, unsignedContent.orElse((Component)null), filterMask);
        });
    });
    private static final UUID SYSTEM_SENDER = Util.NIL_UUID;
    public static final Duration MESSAGE_EXPIRES_AFTER_SERVER = Duration.ofMinutes(5L);
    public static final Duration MESSAGE_EXPIRES_AFTER_CLIENT = MESSAGE_EXPIRES_AFTER_SERVER.plus(Duration.ofMinutes(2L));

    public static PlayerChatMessage system(String content) {
        return unsigned(SYSTEM_SENDER, content);
    }

    public static PlayerChatMessage unsigned(UUID sender, String content) {
        SignedMessageBody signedMessageBody = SignedMessageBody.unsigned(content);
        SignedMessageLink signedMessageLink = SignedMessageLink.unsigned(sender);
        return new PlayerChatMessage(signedMessageLink, (MessageSignature)null, signedMessageBody, (Component)null, FilterMask.PASS_THROUGH);
    }

    public PlayerChatMessage withUnsignedContent(Component unsignedContent) {
        Component component = !unsignedContent.equals(Component.literal(this.signedContent())) ? unsignedContent : null;
        return new PlayerChatMessage(this.link, this.signature, this.signedBody, component, this.filterMask);
    }

    public PlayerChatMessage removeUnsignedContent() {
        return this.unsignedContent != null ? new PlayerChatMessage(this.link, this.signature, this.signedBody, (Component)null, this.filterMask) : this;
    }

    public PlayerChatMessage filter(FilterMask filterMask) {
        return this.filterMask.equals(filterMask) ? this : new PlayerChatMessage(this.link, this.signature, this.signedBody, this.unsignedContent, filterMask);
    }

    public PlayerChatMessage filter(boolean enabled) {
        return this.filter(enabled ? this.filterMask : FilterMask.PASS_THROUGH);
    }

    public static void updateSignature(SignatureUpdater.Output updater, SignedMessageLink link, SignedMessageBody body) throws SignatureException {
        updater.update(Ints.toByteArray(1));
        link.updateSignature(updater);
        body.updateSignature(updater);
    }

    public boolean verify(SignatureValidator verifier) {
        return this.signature != null && this.signature.verify(verifier, (updater) -> {
            updateSignature(updater, this.link, this.signedBody);
        });
    }

    public String signedContent() {
        return this.signedBody.content();
    }

    public Component decoratedContent() {
        return Objects.requireNonNullElseGet(this.unsignedContent, () -> {
            return Component.literal(this.signedContent());
        });
    }

    public Instant timeStamp() {
        return this.signedBody.timeStamp();
    }

    public long salt() {
        return this.signedBody.salt();
    }

    public boolean hasExpiredServer(Instant currentTime) {
        return currentTime.isAfter(this.timeStamp().plus(MESSAGE_EXPIRES_AFTER_SERVER));
    }

    public boolean hasExpiredClient(Instant currentTime) {
        return currentTime.isAfter(this.timeStamp().plus(MESSAGE_EXPIRES_AFTER_CLIENT));
    }

    public UUID sender() {
        return this.link.sender();
    }

    public boolean isSystem() {
        return this.sender().equals(SYSTEM_SENDER);
    }

    public boolean hasSignature() {
        return this.signature != null;
    }

    public boolean hasSignatureFrom(UUID sender) {
        return this.hasSignature() && this.link.sender().equals(sender);
    }

    public boolean isFullyFiltered() {
        return this.filterMask.isFullyFiltered();
    }
}
