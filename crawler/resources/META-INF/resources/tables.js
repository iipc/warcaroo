import {TabulatorFull} from "/webjars/tabulator-tables/6.2.1/dist/js/tabulator_esm.js";
import * as luxon from "/webjars/luxon/3.4.4/build/es6/luxon.js";

window.luxon = luxon;

for (const cssFile of ["/webjars/tabulator-tables/6.2.1/dist/css/tabulator.min.css",
    "/webjars/tabulator-tables/6.2.1/dist/css/tabulator_simple.min.css"]) {
    const link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = cssFile;
    document.head.appendChild(link);
}

/**
 * @typedef {import("tabulator-tables").TabulatorFull} TabulatorFull
 */
export class WarcBotTabulator extends TabulatorFull {
    /**
     * @param {string | HTMLElement} element
     * @param {import("tabulator-tables").Options} options
     */
    constructor(element, options) {
        /** @type {import("tabulator-tables").Options} */
        const defaultOptions = {

            /**
             * @typedef {object} AjaxParams
             * @property {import("tabulator-tables").Filter[]} filter
             * @property {number} page
             * @property {number} size
             * @property {import("tabulator-tables").Sorter[]} sort
             */

            /**
             * @param url
             * @param config
             * @param {AjaxParams} params
             * @returns {string}
             */
            ajaxURLGenerator: function (url, config, params) {
                console.log(params);
                window.params = params;
                const query =  new URLSearchParams();
                for (const filter of params.filter ?? []) {
                    query.set(filter.field, filter.value);
                }
                if (params && params.sort.length > 0) {
                    query.set('sort', params.sort.map(sort => (sort.dir === 'desc' ? '-' : '') + sort.field).join(","));
                }
                query.set('limit', params.size);
                query.set('page', params.page);
                return url + "?" + query;
            },
            persistenceReaderFunc: fragmentReader,
            persistenceWriterFunc: fragmentWriter,
        };

        const mergedOptions = {
            ...defaultOptions,
            ...options
        };

        super(element, mergedOptions);

        addFragmentListener(this);
    }
}

/**
 * Format a number as a human-readable byte size like "24 KB".
 *
 * @param n {number}
 * @returns {string}
 */
export function formatSize(n) {
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let i = 0;
    while (n >= 1024 && ++i < units.length) n /= 1024;
    return `${n.toFixed(n > 0 && n < 10 ? 1 : 0)} ${units[i]}`;
}

function fragmentReader(id, type) {
    var state = Object.fromEntries(new URLSearchParams(location.hash.substring(1)));
    if (type === 'sort' && state.sort) {
        return state.sort.split(',').map(s => s.startsWith("-") ?
            {column:s.substring(1), dir:"desc"} : {column:s, dir:"asc"});
    } else if (type === 'headerFilter') {
        const filters = [];
        for (const column of this.getColumnLayout()) {
            const value = state[column.field];
            if (value) {
                filters.push({field: column.field, type: 'like', value: value});
            }
        }
        return filters;
    }
    return false;
}

function fragmentWriter(id, type, data) {
    var state = Object.fromEntries(new URLSearchParams(location.hash.substring(1)));
    if (type === 'sort') {
        state.sort = data.map(item => (item.dir === 'desc' ? '-' : '') + item.column).join(',');
    } else if (type === 'headerFilter') {
        for (const filter of data) {
            state[filter.field] = filter.value;
        }
    }
    window.history.replaceState(undefined, undefined, "#" + new URLSearchParams(state).toString());
}

function addFragmentListener(table) {
    window.addEventListener('hashchange', event => {
        table.setSort(table.modules.persistence.load('sort'));
        table.clearHeaderFilter();
        for (const filter of table.modules.persistence.load('headerFilter')) {
            table.setHeaderFilterValue(filter.field, filter.value);
        }
    });
}