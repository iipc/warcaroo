package org.netpreserve.warcbot.cdp.domains;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.jetbrains.annotations.NotNull;
import org.netpreserve.warcbot.cdp.Unwrap;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public interface Page {
    Navigate navigate(String url);

    void resetNavigationHistory();

    void close();

    void enable();

    void onLifecycleEvent(Consumer<LifecycleEvent> handler);

    @Unwrap
    FrameTree getFrameTree();

    @Unwrap
    Runtime.ExecutionContextId createIsolatedWorld(FrameId frameId, String worldName, boolean grantUniversalAccess);

    @Unwrap("identifier")
    ScriptIdentifier addScriptToEvaluateOnNewDocument(String source, String worldName);

    void setLifecycleEventsEnabled(boolean enabled);

    record LifecycleEvent(FrameId frameId, Network.LoaderId loaderId, String name, Network.MonotonicTime timestamp) {
    }

    record ScriptIdentifier(@JsonValue String value) {
        @JsonCreator
        public ScriptIdentifier {
            Objects.requireNonNull(value);
        }
    }

    record FrameId(@JsonValue String value) {
        @JsonCreator
        public FrameId {
            Objects.requireNonNull(value);
        }
    }

    record FrameTree(Frame frame, List<FrameTree> childFrames) {
    }

    record Frame(@NotNull FrameId id, FrameId parentId, @NotNull Network.LoaderId loaderId, String name,
                 @NotNull String url, String urlFragment, String domainAndRegistry, String securityOrigin,
                 String mimeType, String unreachableUrl) {
    }

    record Navigate(FrameId frameId, Network.LoaderId loaderId, String errorText) {
    }

    record LoadEventFired(double timestamp) {
    }
}
