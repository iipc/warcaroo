package org.netpreserve.warcbot.cdp;


import java.util.List;

public interface Target {
    void setDiscoverTargets(boolean discover);

    GetTargets getTargets();

    CreateTarget createTarget(String url, boolean newWindow);

    AttachToTarget attachToTarget(String targetId, boolean flatten);

    record CreateTarget(String targetId) {
    }

    record GetTargets(List<TargetInfo> targetInfos) {
    }

    record AttachToTarget(String sessionId) {
    }

    record TargetInfo(String targetId, String type, String title, String url, boolean attached,
                      boolean canAccessOpener, String openerFrameId, String browserContextId,
                      String subtype) {
    }
}
