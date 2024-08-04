function forceEagerLoading(img) {
    if (img.loading === 'lazy') {
        img.loading = 'eager';
    }
}

function loadAllSrcset(img) {
    if (img.srcset) {
        img.srcset.split(',').forEach(src => {
            new Image().src = src.trim().split(' ')[0];
        });
        if (img.hasAttribute('src')) {
            new Image().src = img.getAttribute('src');
        }
    }
}

function loadUrlsFromCSSRule(rule) {
    const urlRegex = /url\s*\(\s*['"]?([^'"()]+)['"]?\s*\)/g;
    for (const match of rule.cssText.matchAll(urlRegex)) {
        new Image().src = match[1];
    }
}

function processStylesheet(stylesheet) {
    try {
        Array.from(stylesheet.cssRules).forEach(rule => {
            switch (rule.constructor.name) {
                case 'CSSStyleRule':
                    loadUrlsFromCSSRule(rule);
                    break;
                case 'CSSImportRule':
                    processStylesheet(rule.styleSheet);
                    break;
                case 'CSSMediaRule':
                    Array.from(rule.cssRules).forEach(loadUrlsFromCSSRule);
                    break;
            }
        });
    } catch (e) {
        console.warn('Cannot read cssRules from stylesheet', stylesheet, e);
    }
}

document.addEventListener("DOMContentLoaded", () => {
    document.querySelectorAll('img[srcset]').forEach(loadAllSrcset);
    document.querySelectorAll('img[loading="lazy"]').forEach(forceEagerLoading);
    Array.from(document.styleSheets).forEach(processStylesheet);
});

const observer = new MutationObserver(mutations => {
    mutations.forEach(mutation => {
        if (mutation.type === 'attributes') {
            if (mutation.attributeName === 'srcset') loadAllSrcset(mutation.target);
            if (mutation.attributeName === 'loading') forceEagerLoading(mutation.target);
        } else if (mutation.type === 'childList') {
            mutation.addedNodes.forEach(node => {
                if (node.nodeType !== Node.ELEMENT_NODE) return;
                switch (node.tagName) {
                    case 'IMG':
                        loadAllSrcset(node);
                        forceEagerLoading(node);
                        break;
                    case 'LINK':
                        if (node.rel === 'stylesheet') {
                            node.addEventListener('load', () => processStylesheet(node.sheet));
                        }
                        break;
                    case 'STYLE':
                        processStylesheet(node.sheet);
                        break;
                    default:
                        node.querySelectorAll('img').forEach(img => {
                            loadAllSrcset(img);
                            forceEagerLoading(img);
                        });
                }
            });
        }
    });
});

observer.observe(document.body, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ['srcset', 'loading']
});