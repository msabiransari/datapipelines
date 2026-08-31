/*
 * Dependency-free SQL highlighting for the node details panel (pipeline-editor.md §8).
 *
 * A single-pass, index-based tokenizer — no regex with alternation over the whole
 * input, no backtracking, so a 10KB unterminated string literal terminates the token
 * stream instead of hanging (sql-highlight.test.mjs asserts the wall-clock bound).
 *
 * THE SECURITY-CRITICAL DECISION is the escaping order: tokenize the RAW SQL, then
 * escape each token's text as it is emitted. Never escape first and tokenize the
 * escaped string — `&lt;` would tokenize as three tokens and an `&` inside a string
 * literal would corrupt the output. highlight() returns HTML assigned with innerHTML,
 * so every path that puts text into that string escapes &, < and > first.
 *
 * Reconstructibility is the contract: tokenize(sql).map(t => t.text).join("") === sql
 * for every input — an unrecognized character becomes a `plain` token, never a dropped
 * one (a tokenizer that eats whitespace looks fine until a real query loses a newline).
 *
 * Exports { tokenize, highlight, apply } for `node --test`; in the browser it
 * additionally publishes window.DpSqlHighlight. apply(root) re-highlights every
 * code.pe-sql-code inside a freshly swapped partial (init.js's SQL loader).
 */
(function () {
  "use strict";

  // Deliberately minimal and dialect-agnostic, matched case-insensitively on word
  // boundaries. This is highlighting, not parsing: a word NOT in the set renders
  // `plain` and loses nothing. Multi-word constructs (GROUP BY, ORDER BY) highlight
  // as their single words — BY is in the set for exactly that reason.
  var KEYWORDS = {};
  [
    "SELECT", "FROM", "WHERE", "GROUP", "BY", "ORDER", "HAVING", "LIMIT", "OFFSET",
    "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "ON", "AS", "AND", "OR", "NOT",
    "NULL", "IS", "IN", "INSERT", "UPDATE", "DELETE", "SET", "VALUES", "CREATE",
    "TABLE", "DROP", "ALTER", "WITH", "UNION", "ALL", "DISTINCT", "CASE", "WHEN",
    "THEN", "ELSE", "END", "COUNT", "SUM", "AVG", "MIN", "MAX",
  ].forEach(function (k) { KEYWORDS[k] = true; });

  function isIdentStart(c) {
    return (c >= "a" && c <= "z") || (c >= "A" && c <= "Z") || c === "_";
  }

  function isIdentPart(c) {
    return isIdentStart(c) || (c >= "0" && c <= "9") || c === "$";
  }

  function isDigit(c) {
    return c >= "0" && c <= "9";
  }

  /*
   * One pass over the string with an index. Every branch advances the index by at
   * least one character, so termination is structural. Kinds: keyword, string,
   * comment, number, parameter, punct, plain.
   */
  function tokenize(sql) {
    var tokens = [];
    var i = 0;
    var n = sql.length;

    function push(kind, start, end) {
      tokens.push({ kind: kind, text: sql.substring(start, end) });
    }

    while (i < n) {
      var c = sql.charAt(i);
      var start = i;

      // Line comment: -- to end of line.
      if (c === "-" && sql.charAt(i + 1) === "-") {
        i += 2;
        while (i < n && sql.charAt(i) !== "\n") i++;
        push("comment", start, i);
        continue;
      }

      // Block comment: /* ... */ — unterminated consumes to EOF.
      if (c === "/" && sql.charAt(i + 1) === "*") {
        i += 2;
        while (i < n && !(sql.charAt(i) === "*" && sql.charAt(i + 1) === "/")) i++;
        i = Math.min(i + 2, n);
        push("comment", start, i);
        continue;
      }

      // String literal: '...' with the SQL '' doubled-quote escape — unterminated
      // consumes to EOF and ends the stream, it never hangs.
      if (c === "'") {
        i++;
        while (i < n) {
          if (sql.charAt(i) === "'") {
            if (sql.charAt(i + 1) === "'") {
              i += 2; // escaped quote, keep scanning
            } else {
              i++; // closing quote
              break;
            }
          } else {
            i++;
          }
        }
        push("string", start, i);
        continue;
      }

      // Freemarker interpolation ${name} — unterminated consumes to EOF.
      if (c === "$" && sql.charAt(i + 1) === "{") {
        i += 2;
        while (i < n && sql.charAt(i) !== "}") i++;
        i = Math.min(i + 1, n);
        push("parameter", start, i);
        continue;
      }

      // Named parameter :name — but never the :: cast operator.
      if (c === ":" && isIdentStart(sql.charAt(i + 1))) {
        i += 2;
        while (i < n && isIdentPart(sql.charAt(i))) i++;
        push("parameter", start, i);
        continue;
      }

      // Number: digits with an optional fractional part.
      if (isDigit(c)) {
        i++;
        while (i < n && (isDigit(sql.charAt(i)) || sql.charAt(i) === ".")) i++;
        push("number", start, i);
        continue;
      }

      // Identifier or keyword.
      if (isIdentStart(c)) {
        i++;
        while (i < n && isIdentPart(sql.charAt(i))) i++;
        var word = sql.substring(start, i);
        push(KEYWORDS[word.toUpperCase()] ? "keyword" : "plain", start, i);
        continue;
      }

      // Whitespace run — plain, never dropped.
      if (c === " " || c === "\t" || c === "\n" || c === "\r") {
        i++;
        while (i < n && " \t\n\r".indexOf(sql.charAt(i)) !== -1) i++;
        push("plain", start, i);
        continue;
      }

      // Punctuation and operators — one character each.
      if ("(),;.+-*/%=<>|".indexOf(c) !== -1) {
        push("punct", start, ++i);
        continue;
      }

      // Anything unrecognized is still a token: nothing is ever lost.
      push("plain", start, ++i);
    }
    return tokens;
  }

  function escapeHtml(text) {
    return text
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;");
  }

  /* Tokens to HTML. `plain` needs no span; every span carries its kind class. */
  function highlight(sql) {
    return tokenize(sql)
      .map(function (t) {
        var text = escapeHtml(t.text);
        if (t.kind === "plain") return text;
        return '<span class="pe-sql-tok-' + t.kind + '">' + text + "</span>";
      })
      .join("");
  }

  /* Re-highlight every SQL block inside a freshly swapped node-SQL partial. */
  function apply(root) {
    if (!root || !root.querySelectorAll) return;
    var blocks = root.querySelectorAll("code.pe-sql-code");
    for (var i = 0; i < blocks.length; i++) {
      blocks[i].innerHTML = highlight(blocks[i].textContent);
    }
  }

  var api = { tokenize: tokenize, highlight: highlight, apply: apply };
  if (typeof module !== "undefined" && module.exports) module.exports = api; // node --test
  if (typeof window !== "undefined") window.DpSqlHighlight = api;
})();
