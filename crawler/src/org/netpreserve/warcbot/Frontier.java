package org.netpreserve.warcbot;

import de.malkusch.whoisServerList.publicSuffixList.PublicSuffixList;
import de.malkusch.whoisServerList.publicSuffixList.PublicSuffixListFactory;
import org.jetbrains.annotations.Nullable;
import org.netpreserve.warcbot.util.Url;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;

import static org.netpreserve.warcbot.FrontierUrl.State.OUT_OF_SCOPE;

public class Frontier {
    private static final Logger log = LoggerFactory.getLogger(Frontier.class);
    private static final PublicSuffixList publicSuffixList = new PublicSuffixListFactory().build();
    private final Database db;
    private final Predicate<String> scope;
    private final Config config;
    private final Set<Long> lockedHosts = new HashSet<>();

    public Frontier(Database db, Predicate<String> scope, Config config) {
        this.db = db;
        this.scope = scope;
        this.config = config;
    }

    public void addUrl(Url url, int depth, Url via) {
        addUrls(Collections.singleton(url), depth, via);
    }

    public void addUrls(Collection<Url> urls, int depth, Url via) {
        int novel = 0;
        for (var url : urls) {
            if (!url.isHttp()) continue;
            url = url.withoutFragment();
            if (!scope.test(url.toString())) continue;

            String domain = publicSuffixList.getRegistrableDomain(url.host());
            if (domain == null) {
                log.warn("Unable to get domain for {}", url);
                domain = url.host();
            }
            String rhost = url.rhost();
            String rdomain = Url.reverseHost(domain);
            Instant now = Instant.now();

            Long id = addUrl(url, depth, via, rhost, rdomain, now);
            if (id != null) novel++;
        }
        log.info("Added {} new URLs from {} extracted links", novel, urls.size());
    }

    private Long addUrl(Url url, int depth, Url via, String rhost, String rdomain, Instant now) {
        return db.inTransaction(dao -> {
            if (dao.frontier().findUrl(url) != null) return null;
            long hostId = dao.hosts().insertOrGetId(rhost);
            long domainId = dao.domains().insertOrGetId(rdomain);
            Long id = dao.frontier().addUrl0(url, hostId, domainId, depth, via, now, FrontierUrl.State.PENDING);
            if (id != null) {
                dao.hosts().incrementPendingAndInitNextVisit(hostId);
                dao.domains().incrementPending(domainId);
            }
            return id;
        });
    }

    public synchronized @Nullable FrontierUrl takeNext() {
        while (true) {
            Long hostId = db.hosts().findNextToVisit(Instant.now(), lockedHosts);
            if (hostId == null) return null;
            FrontierUrl frontierUrl = db.frontier().nextUrlForHost(hostId);
            if (frontierUrl == null) {
                db.hosts().clearNextVisitIfNoPendingUrls(hostId);
                continue;
            }
            lockedHosts.add(hostId);
            // re-test scope in case it has changed
            if (!scope.test(frontierUrl.url().toString())) {
                release(frontierUrl, OUT_OF_SCOPE);
                continue;
            }
            return frontierUrl;
        }
    }

    public synchronized void release(FrontierUrl frontierUrl, FrontierUrl.State newState) {
        Instant now = Instant.now();
        db.useTransaction(db -> {
            db.frontier().updateState(frontierUrl.id(), newState);
            db.hosts().updateOnFrontierUrlStateChange(frontierUrl.hostId(), frontierUrl.state(), newState, now, now.plusMillis(config.getCrawlDelay()));
            db.domains().updateMetricsOnFrontierUrlStateChange(frontierUrl.domainId(), frontierUrl.state(), newState);
        });
        lockedHosts.remove(frontierUrl.hostId());
    }
}
