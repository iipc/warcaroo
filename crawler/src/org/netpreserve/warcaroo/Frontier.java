package org.netpreserve.warcaroo;

import de.malkusch.whoisServerList.publicSuffixList.PublicSuffixList;
import de.malkusch.whoisServerList.publicSuffixList.PublicSuffixListFactory;
import org.jetbrains.annotations.Nullable;
import org.netpreserve.warcaroo.config.CrawlConfig;
import org.netpreserve.warcaroo.config.LimitsConfig;
import org.netpreserve.warcaroo.util.Url;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;

import static org.netpreserve.warcaroo.FrontierUrl.State.OUT_OF_SCOPE;

public class Frontier {
    private static final Logger log = LoggerFactory.getLogger(Frontier.class);
    private static final PublicSuffixList publicSuffixList = new PublicSuffixListFactory().build();
    private final Database db;
    private final Predicate<Url> scope;
    private final Set<Long> lockedHosts = new HashSet<>();
    private final CrawlConfig crawlConfig;

    public Frontier(Database db, Predicate<Url> scope, CrawlConfig crawlConfig) {
        this.db = db;
        this.scope = scope;
        this.crawlConfig = crawlConfig;
    }

    public void addUrl(Url url, int depth, Url via) {
        addUrls(Collections.singleton(url), depth, via);
    }

    public void addUrls(Collection<Url> urls, int depth, Url via) {
        int novel = 0;
        for (var url : urls) {
            if (!url.isHttp()) continue;
            url = url.withoutFragment();
            if (!scope.test(url)) continue;

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
                dao.progress().incrementPendingAndDiscovered();
            }
            return id;
        });
    }

    public synchronized @Nullable FrontierUrl takeNext() throws CrawlLimitException {
        while (true) {
            LimitsConfig limits = crawlConfig.limits();
            if (limits != null) {
                Progress progress = db.progress().current();
                if (limits.pages() != null && progress.crawled() >= limits.pages()) {
                    throw new CrawlLimitException("page limit reached");
                } else if (limits.bytes() != null && progress.size() >= limits.bytes()) {
                    throw new CrawlLimitException("size limit reached");
                }
            }
            Long hostId = db.hosts().findNextToVisit(Instant.now(), lockedHosts);
            if (hostId == null) return null;
            FrontierUrl frontierUrl = db.frontier().nextUrlForHost(hostId);
            if (frontierUrl == null) {
                db.hosts().clearNextVisitIfNoPendingUrls(hostId);
                continue;
            }
            lockedHosts.add(hostId);
            // re-test scope in case it has changed
            if (!scope.test(frontierUrl.url())) {
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
            db.hosts().updateOnFrontierUrlStateChange(frontierUrl.hostId(), frontierUrl.state(), newState, now, now.plusMillis(crawlConfig.delay()));
            db.domains().updateMetricsOnFrontierUrlStateChange(frontierUrl.domainId(), frontierUrl.state(), newState);
            if (newState == FrontierUrl.State.CRAWLED) {
                db.progress().decrementPendingAndIncrementCrawled();
            } else if (newState == FrontierUrl.State.FAILED) {
                db.progress().decrementPendingAndIncrementFailed();
            } else if (newState != FrontierUrl.State.PENDING) {
                db.progress().decrementPending();
            }
        });
        lockedHosts.remove(frontierUrl.hostId());
    }
}
