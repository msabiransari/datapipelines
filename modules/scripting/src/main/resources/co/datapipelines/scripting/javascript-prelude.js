// The JavaScript engine's prelude (#8 lane 8a, transform-nodes design §4.4). It runs as an
// INTERNAL source (its statements are not counted against sandbox.MaxStatements) in every Context
// before the author's source, and evaluates to the one function the host calls: run(main, json,
// now, maxResultChars, maxNesting).
//
// AN AID, NOT A BOUNDARY ("cooperative determinism"). The sandbox (the isolate, its limits, the
// UNTRUSTED policy) is the boundary, and the host's strict decode of the result string is the
// boundary for the result's shape. Everything here makes an honest body's mistakes loud — an
// unpinned clock, Math.random, a Map where JSON was meant — and a body that works around it gains
// nothing the sandbox did not already allow.
//
// Host contract: errors the host must classify carry a tag at the start of the message:
//   __dp_refused:      a refused clock or entropy read (recorded, so a body that catches it still fails)
//   __dp_shape:        the result is not JSON-shaped ("result is not JSON-shaped: <path> is <kind>")
//   __dp_result_bytes: the result string is past the result budget (an early aid; the host measures again)
//   __dp_depth:        the result nests deeper than the host's decoder accepts
(function (global) {
    'use strict';

    // Intrinsics captured before the author's code can touch them.
    const RealDate = global.Date;
    const realDateProto = RealDate.prototype;
    const stringify = JSON.stringify;
    const parse = JSON.parse;
    const keys = Object.keys;
    const getPrototypeOf = Object.getPrototypeOf;
    const defineProperty = Object.defineProperty;
    const freeze = Object.freeze;
    const isArray = Array.isArray;
    const ObjectProto = Object.prototype;
    const hasOwn = Object.prototype.hasOwnProperty;
    const apply = Reflect.apply;
    const construct = Reflect.construct;
    const StringCtor = String;
    const print = global.print;
    const RealDateTimeFormat = global.Intl && global.Intl.DateTimeFormat;
    const toStringTag = Symbol.toStringTag;

    const REFUSED = '__dp_refused:';
    const SHAPE = '__dp_shape:';
    const RESULT_BYTES = '__dp_result_bytes:';
    const DEPTH = '__dp_depth:';

    // Per-evaluation state, set by run() before main.
    let pinned = null;
    let refusal = null;

    function refuse(message) {
        if (refusal === null) refusal = message;
        throw new Error(REFUSED + message);
    }

    function nowMillis(what) {
        if (pinned === null) refuse(what + ' refused: no pinned clock');
        return pinned;
    }

    // ---- the clock: every no-argument read of "now" returns the pinned instant or refuses ----------
    // Named Date, so a refused instance reads "a Date" and Date.name is unchanged.
    const PinnedDate = function Date(...args) {
        if (new.target === undefined) {
            // Date() called as a function returns the current time as a string.
            return apply(realDateProto.toString, construct(RealDate, [nowMillis('Date()')]), []);
        }
        const instance = args.length === 0 ? construct(RealDate, [nowMillis('new Date()')]) : construct(RealDate, args);
        // A subclass (class X extends Date) keeps its own prototype.
        if (new.target !== PinnedDate) Reflect.setPrototypeOf(instance, new.target.prototype);
        return instance;
    };
    defineProperty(PinnedDate, 'prototype', { value: realDateProto, writable: false });
    defineProperty(PinnedDate, 'now', {
        value: function now() {
            return nowMillis('Date.now()');
        },
        writable: true,
        configurable: true,
    });
    defineProperty(PinnedDate, 'UTC', { value: RealDate.UTC, writable: true, configurable: true });
    defineProperty(PinnedDate, 'parse', { value: RealDate.parse, writable: true, configurable: true });
    defineProperty(realDateProto, 'constructor', { value: PinnedDate, writable: true, configurable: true });
    defineProperty(global, 'Date', { value: PinnedDate, writable: true, configurable: true });

    // Intl.DateTimeFormat formats "now" when it is given no date.
    if (RealDateTimeFormat) {
        const proto = RealDateTimeFormat.prototype;
        const formatGetter = Reflect.getOwnPropertyDescriptor(proto, 'format').get;
        const formatToParts = proto.formatToParts;
        const pin = (date, what) => (date === undefined ? nowMillis(what) : date);
        defineProperty(proto, 'format', {
            get: function () {
                const bound = apply(formatGetter, this, []);
                return (date) => bound(pin(date, 'Intl.DateTimeFormat format()'));
            },
            configurable: true,
        });
        defineProperty(proto, 'formatToParts', {
            value: function (date) {
                return apply(formatToParts, this, [pin(date, 'Intl.DateTimeFormat formatToParts()')]);
            },
            writable: true,
            configurable: true,
        });
    }

    // ---- entropy: no seed exists this round, so random is refused ------------------------------------
    defineProperty(Math, 'random', {
        value: function random() {
            return refuse('Math.random() refused: a script is a pure function of its inputs');
        },
        writable: true,
        configurable: true,
    });

    // ---- console: every level on ONE stream, so order holds across levels ---------------------------
    function show(value) {
        if (typeof value === 'string') return value;
        if (typeof value === 'bigint') return StringCtor(value) + 'n';
        if (typeof value === 'object' && value !== null) {
            try {
                const text = stringify(value, (k, v) => (typeof v === 'bigint' ? StringCtor(v) : v));
                if (text !== undefined) return text;
            } catch (ignored) {
                // a cycle or a throwing getter: fall back to String()
            }
        }
        try {
            return StringCtor(value);
        } catch (ignored) {
            return '[unprintable]';
        }
    }
    function writer(level) {
        return function (...args) {
            let text = '';
            for (let i = 0; i < args.length; i++) text += (i === 0 ? '' : ' ') + show(args[i]);
            print(level + '\u0001' + text);
        };
    }
    defineProperty(global, 'console', {
        value: freeze({
            log: writer('LOG'),
            info: writer('INFO'),
            debug: writer('DEBUG'),
            warn: writer('WARN'),
            error: writer('ERROR'),
        }),
        writable: true,
        configurable: true,
    });

    // ---- globals that name nothing a body may use ---------------------------------------------------
    // `Java` and the package globals reach no host class under the UNTRUSTED policy (a lookup is a
    // TypeError); deleting them makes a reference the plain ReferenceError of a name that does not exist.
    for (const name of ['print', 'printErr', 'Java', 'Packages', 'java', 'javax', 'javafx', 'com', 'org', 'edu', 'Graal', 'load', 'loadWithNewGlobal']) {
        delete global[name];
    }

    // ---- the result-shape check (an aid: the host's strict decode is the boundary) ------------------
    function kindOf(v) {
        if (v === undefined) return 'undefined';
        if (typeof v === 'number') return StringCtor(v);
        if (typeof v === 'function') return 'a function';
        if (typeof v === 'symbol') return 'a symbol';
        let name;
        try {
            name = v[toStringTag] || (v.constructor && v.constructor.name);
        } catch (ignored) {
            name = undefined;
        }
        return name ? 'a ' + name : 'not a plain object';
    }
    function shapeError(path, kind) {
        return new Error(SHAPE + 'result is not JSON-shaped: ' + path + ' is ' + kind);
    }
    function isThenable(v) {
        try {
            return typeof v.then === 'function';
        } catch (ignored) {
            return false;
        }
    }
    // Iterative, so a deep result cannot overflow the guest stack here; ancestors are tracked per
    // path, so a value shared by two branches is legal and only a true cycle is refused.
    function check(result, maxNesting) {
        const stack = [{ value: result, path: 'result', parent: null, depth: 0 }];
        while (stack.length > 0) {
            const frame = stack.pop();
            const v = frame.value;
            const t = typeof v;
            if (v === null || t === 'string' || t === 'boolean' || t === 'bigint') continue;
            if (t === 'number') {
                if (v !== v || v === Infinity || v === -Infinity) throw shapeError(frame.path, kindOf(v));
                continue;
            }
            if (t !== 'object') throw shapeError(frame.path, kindOf(v));
            if (isThenable(v)) throw shapeError(frame.path, 'a Promise (main must return synchronously)');
            if (frame.depth >= maxNesting) throw new Error(DEPTH + 'the result nests deeper than ' + maxNesting + ' levels at ' + frame.path);
            for (let a = frame.parent; a !== null; a = a.parent) {
                if (a.value === v) throw shapeError(frame.path, 'a cycle back to ' + a.path);
            }
            if (isArray(v)) {
                if (getPrototypeOf(v) !== Array.prototype) throw shapeError(frame.path, kindOf(v));
                for (let i = v.length - 1; i >= 0; i--) {
                    if (!apply(hasOwn, v, [i])) throw shapeError(frame.path + '[' + i + ']', 'a hole');
                    if (v[i] === undefined) throw shapeError(frame.path + '[' + i + ']', 'undefined');
                    stack.push({ value: v[i], path: frame.path + '[' + i + ']', parent: frame, depth: frame.depth + 1 });
                }
                continue;
            }
            const proto = getPrototypeOf(v);
            if (proto !== ObjectProto && proto !== null) throw shapeError(frame.path, kindOf(v));
            const names = keys(v);
            for (let i = names.length - 1; i >= 0; i--) {
                const child = v[names[i]];
                // An undefined property is DROPPED (the JS idiom; JSON.stringify drops it too).
                if (child === undefined) continue;
                stack.push({ value: child, path: frame.path + '.' + names[i], parent: frame, depth: frame.depth + 1 });
            }
        }
    }

    function bigintAsString(k, v) {
        return typeof v === 'bigint' ? StringCtor(v) : v;
    }

    // ---- the entry: JSON in, main, the shape check, JSON out ----------------------------------------
    return function run(main, json, now, maxResultChars, maxNesting) {
        pinned = now;
        refusal = null;
        const input = parse(json);
        const result = main(input);
        if (refusal !== null) throw new Error(REFUSED + refusal);
        if (result === undefined) throw new Error(SHAPE + 'main returned nothing');
        check(result, maxNesting);
        const text = stringify(result, bigintAsString);
        if (refusal !== null) throw new Error(REFUSED + refusal);
        if (text.length > maxResultChars) {
            throw new Error(RESULT_BYTES + 'the result is ' + text.length + ' characters, past the budget of ' + maxResultChars);
        }
        return text;
    };
})(globalThis)
