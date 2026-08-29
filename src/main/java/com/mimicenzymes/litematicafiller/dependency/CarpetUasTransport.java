package com.mimicenzymes.litematicafiller.dependency;

import com.mimicenzymes.litematicafiller.network.CarpetUasOpenPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reflection-only boundary for Carpet UAS Addition (mod id: carpet-uas-addition).
 * <p>
 * Legacy open calls keep their original fire-and-forget semantics. Open-v2
 * exposes a request id and an authoritative acknowledgement without making
 * Carpet UAS a required compile/runtime dependency. When the Carpet UAS
 * client mod is absent, the legacy open payload can still be sent through a
 * locally registered wire-compatible stand-in (see {@link CarpetUasOpenPayload}).
 */
public final class CarpetUasTransport {
    private static final String OPEN_PAYLOAD_CLASS =
            "com.mystia.carpetuas.network.ShulkerBoxOpenPayload";
    private static final String ACCESS_V2_CLIENT_CLASS =
            "com.mystia.carpetuas.network.ShulkerBoxAccessV2Client";

    private static boolean resolved;
    private static Object openPayloadInstance;
    private static boolean localOpenPayloadRegistered;
    private static boolean accessV2Resolved;
    private static Method accessV2CanOpen;
    private static Method accessV2SendOpen;
    private static Method accessV2PollOpenResponse;
    private static Method accessV2ForgetOpen;
    private static Method openResponseProtocolVersion;
    private static Method openResponseRequestId;
    private static Method openResponseStatusCode;
    private static Method openResponseShulkerSlot;
    private static Method openResponseContainerId;
    private static Method openResponseShulkerAfter;

    private CarpetUasTransport() {
    }

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        if (!DependencyChecker.HAS_CARPET_UAS) return;
        try {
            Class<?> payloadClass = Class.forName(OPEN_PAYLOAD_CLASS);
            Field instanceField = payloadClass.getField("INSTANCE");
            openPayloadInstance = instanceField.get(null);
        } catch (ReflectiveOperationException | RuntimeException
                | LinkageError ignored) {
            openPayloadInstance = null;
        }
        resolveAccessV2();
    }

    private static void resolveAccessV2() {
        if (accessV2Resolved) return;
        accessV2Resolved = true;
        try {
            Class<?> clientClass = Class.forName(ACCESS_V2_CLIENT_CLASS);
            accessV2CanOpen = clientClass.getMethod("canOpen");
            accessV2SendOpen = clientClass.getMethod("sendOpen", int.class);
            accessV2PollOpenResponse = clientClass.getMethod(
                    "pollOpenResponse", long.class);
            accessV2ForgetOpen = clientClass.getMethod("forgetOpen", long.class);
            Class<?> responseClass =
                    accessV2PollOpenResponse.getReturnType();
            openResponseProtocolVersion = responseClass.getMethod("protocolVersion");
            openResponseRequestId = responseClass.getMethod("requestId");
            openResponseStatusCode = responseClass.getMethod("statusCode");
            openResponseShulkerSlot = responseClass.getMethod("shulkerSlot");
            openResponseContainerId = responseClass.getMethod("containerId");
            openResponseShulkerAfter = responseClass.getMethod("shulkerAfter");
        } catch (ReflectiveOperationException | RuntimeException
                | LinkageError ignored) {
            clearAccessV2Reflection();
        }
    }

    private static void clearAccessV2Reflection() {
        accessV2CanOpen = null;
        accessV2SendOpen = null;
        accessV2PollOpenResponse = null;
        accessV2ForgetOpen = null;
        openResponseProtocolVersion = null;
        openResponseRequestId = null;
        openResponseStatusCode = null;
        openResponseShulkerSlot = null;
        openResponseContainerId = null;
        openResponseShulkerAfter = null;
    }

    /**
     * Registers the local wire-compatible open payload when the Carpet UAS
     * client mod is missing so the mod can still reach a server-side extension.
     */
    public static void registerLocalOpenPayload() {
        if (DependencyChecker.HAS_CARPET_UAS || localOpenPayloadRegistered) return;
        try {
            net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
                    .serverboundPlay()
                    .register(CarpetUasOpenPayload.ID, CarpetUasOpenPayload.CODEC);
            localOpenPayloadRegistered = true;
        } catch (RuntimeException | LinkageError ignored) {
            localOpenPayloadRegistered = false;
        }
    }

    /** Transport capability only; it does not prove the Carpet rule. */
    public static boolean canSendOpenPayload() {
        if (DependencyChecker.HAS_CARPET_UAS) {
            resolve();
            if (!(openPayloadInstance instanceof CustomPacketPayload payload)) {
                return false;
            }
            try {
                return ClientPlayNetworking.canSend(payload.type());
            } catch (RuntimeException | LinkageError ignored) {
                return false;
            }
        }
        try {
            return localOpenPayloadRegistered
                    && ClientPlayNetworking.canSend(CarpetUasOpenPayload.ID);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /**
     * Sends one held-shulker open request; success means enqueue only, never
     * a server ACK. The caller must first hold the box in the main hand.
     */
    public static boolean sendOpenPayload() {
        try {
            if (DependencyChecker.HAS_CARPET_UAS) {
                resolve();
                if (canSendOpenPayload() && openPayloadInstance
                        instanceof CustomPacketPayload payload) {
                    ClientPlayNetworking.send(payload);
                    return true;
                }
                return false;
            }
            if (canSendOpenPayload()) {
                ClientPlayNetworking.send(new CarpetUasOpenPayload());
                return true;
            }
            return false;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /** True when the server advertises acknowledged raw-slot menu opens. */
    public static boolean canSendOpenV2() {
        if (!DependencyChecker.HAS_CARPET_UAS) return false;
        resolve();
        resolveAccessV2();
        if (accessV2CanOpen == null) return false;
        try {
            return Boolean.TRUE.equals(accessV2CanOpen.invoke(null));
        } catch (ReflectiveOperationException | RuntimeException
                | LinkageError ignored) {
            return false;
        }
    }

    /** Sends one exact raw-slot open request and returns its request id. */
    public static long sendOpenV2(int shulkerSlot) {
        if (!canSendOpenV2() || accessV2SendOpen == null) return -1L;
        try {
            Object result = accessV2SendOpen.invoke(null, shulkerSlot);
            return result instanceof Long requestId ? requestId : -1L;
        } catch (ReflectiveOperationException | RuntimeException
                | LinkageError ignored) {
            return -1L;
        }
    }

    /** Polls and consumes one authoritative raw-slot open result. */
    public static OpenV2Result pollOpenV2(long requestId) {
        resolve();
        resolveAccessV2();
        if (requestId <= 0L || accessV2PollOpenResponse == null) return null;
        try {
            Object response = accessV2PollOpenResponse.invoke(null, requestId);
            if (response == null) return null;
            return new OpenV2Result(
                    (Integer) openResponseProtocolVersion.invoke(response),
                    (Long) openResponseRequestId.invoke(response),
                    (Integer) openResponseStatusCode.invoke(response),
                    (Integer) openResponseShulkerSlot.invoke(response),
                    (Integer) openResponseContainerId.invoke(response),
                    copyStack(openResponseShulkerAfter.invoke(response))
            );
        } catch (ReflectiveOperationException | RuntimeException
                | LinkageError ignored) {
            return null;
        }
    }

    public static void forgetOpenV2(long requestId) {
        resolve();
        resolveAccessV2();
        if (requestId <= 0L || accessV2ForgetOpen == null) return;
        try {
            accessV2ForgetOpen.invoke(null, requestId);
        } catch (ReflectiveOperationException | RuntimeException
                | LinkageError ignored) {
        }
    }

    private static ItemStack copyStack(Object value) {
        return value instanceof ItemStack stack ? stack.copy() : ItemStack.EMPTY;
    }

    public record OpenV2Result(
            int protocolVersion,
            long requestId,
            int statusCode,
            int shulkerSlot,
            int containerId,
            ItemStack shulkerAfter
    ) {
        public OpenV2Result {
            shulkerAfter = shulkerAfter.copy();
        }

        public boolean opened() {
            return protocolVersion == 2 && statusCode == 0 && containerId > 0;
        }
    }
}
