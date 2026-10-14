#!/usr/bin/env node
/**
 * apply-ratelimit.js -- add @RateLimit annotations to the mini-program and open
 * callback controllers (batch 4, P2-3).
 *
 * Why a script: the same edit has to be applied to several controllers, and it
 * must be re-runnable (idempotent) so that adding a controller later can be done
 * by editing the TARGETS table and re-running.
 *
 * Targets:
 *   - the four /api/mp/** controllers get a CLASS level annotation (every
 *     endpoint on the mini-program side is public-facing and needs a quota);
 *   - the payment notify callback (/api/open/pay/notify/{channel}) gets a METHOD
 *     level annotation. It is anonymous (authenticated by channel signature),
 *     so it is counted per source IP and gets a larger quota: a real channel
 *     retries a handful of times, an attacker must not be able to burn CPU on
 *     signature verification.
 *
 * Usage:  node scripts/tools/apply-ratelimit.js [--dry-run]
 */
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..');
const JAVA = path.join(ROOT, 'backend', 'src', 'main', 'java', 'com', 'company', 'member');
const IMPORT = 'import com.company.member.common.annotation.RateLimit;';
const DRY = process.argv.includes('--dry-run');

/** class level targets: relative java path -> annotation text */
const CLASS_TARGETS = [
  ['mp/controller/MpAuthController.java', '@RateLimit'],
  ['mp/controller/MpMemberController.java', '@RateLimit'],
  ['mp/controller/MpRechargeController.java', '@RateLimit'],
  ['mp/controller/MpReferralController.java', '@RateLimit'],
];

/** method level targets: relative java path + the mapping line it sits on */
const METHOD_TARGETS = [
  [
    'modules/payment/controller/PaymentController.java',
    '@PostMapping("/api/open/pay/notify/{channel}")',
    '@RateLimit(dimension = RateLimit.Dimension.IP, qps = 100, key = "pay-notify",\n' +
      '            message = "回调请求过于频繁")',
  ],
];

/**
 * Insert the RateLimit import, keeping the import block sorted:
 * after the last com.company.member import when one exists, else before the
 * first import of the file.
 */
function addImport(src) {
  if (src.includes(IMPORT)) return { src, changed: false };
  const lines = src.split('\n');
  let lastOwn = -1;
  let firstImport = -1;
  for (let i = 0; i < lines.length; i++) {
    if (/^import\s/.test(lines[i])) {
      if (firstImport === -1) firstImport = i;
      if (/^import com\.company\.member\./.test(lines[i])) lastOwn = i;
    }
  }
  const at = lastOwn >= 0 ? lastOwn + 1 : firstImport;
  if (at < 0) throw new Error('no import block found');
  lines.splice(at, 0, IMPORT);
  return { src: lines.join('\n'), changed: true };
}

/** Insert a class annotation just above `public class`, i.e. after the last annotation. */
function addClassAnnotation(src, annotation) {
  if (new RegExp('^' + annotation.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '\\s*$', 'm').test(src)) {
    return { src, changed: false };
  }
  const lines = src.split('\n');
  const idx = lines.findIndex((l) => /^public class\s/.test(l));
  if (idx < 0) throw new Error('class declaration not found');
  lines.splice(idx, 0, annotation);
  return { src: lines.join('\n'), changed: true };
}

/** Insert a method annotation directly above the mapping line. */
function addMethodAnnotation(src, mappingLine, annotation) {
  const lines = src.split('\n');
  const idx = lines.findIndex((l) => l.trim() === mappingLine);
  if (idx < 0) throw new Error('mapping line not found: ' + mappingLine);
  // already annotated (previous run)?
  if (lines.slice(Math.max(0, idx - 4), idx).some((l) => l.trim().startsWith('@RateLimit'))) {
    return { src, changed: false };
  }
  lines.splice(idx, 0, ...annotation.split('\n'));
  return { src: lines.join('\n'), changed: true };
}

function run() {
  let touched = 0;
  const report = [];

  for (const [rel, annotation] of CLASS_TARGETS) {
    const file = path.join(JAVA, rel.replace(/\//g, path.sep));
    let src = fs.readFileSync(file, 'utf8');
    const before = src;
    const imp = addImport(src);
    src = imp.src;
    src = addClassAnnotation(src, annotation).src;
    if (src !== before) {
      if (!DRY) fs.writeFileSync(file, src, 'utf8');
      touched++;
      report.push('  class  ' + rel + (imp.changed ? '' : ' (import already present)'));
    }
  }

  for (const [rel, mapping, annotation] of METHOD_TARGETS) {
    const file = path.join(JAVA, rel.replace(/\//g, path.sep));
    let src = fs.readFileSync(file, 'utf8');
    const before = src;
    const imp = addImport(src);
    src = imp.src;
    src = addMethodAnnotation(src, mapping, annotation).src;
    if (src !== before) {
      if (!DRY) fs.writeFileSync(file, src, 'utf8');
      touched++;
      report.push('  method ' + rel + ' -> ' + mapping);
    }
  }

  console.log('apply-ratelimit: ' + touched + ' file(s) updated' + (DRY ? ' (dry-run)' : ''));
  report.forEach((l) => console.log(l));
  if (touched === 0) console.log('  nothing to do (already applied)');
}

run();
