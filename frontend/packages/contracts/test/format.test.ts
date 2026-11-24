import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { formatDate, formatRupees, plural } from '../src/index.js';

describe('format helpers', () => {
  it('formats ISO dates like the server', () => {
    assert.equal(formatDate('2026-12-08'), '08 Dec 2026');
    assert.equal(formatDate('2027-09-30'), '30 Sep 2027');
  });

  it('formats rupees, including negative balances', () => {
    assert.equal(formatRupees(500), 'Rs. 500');
    assert.equal(formatRupees(-20), '-Rs. 20');
  });

  it('pluralises', () => {
    assert.equal(plural(1, 'day'), '1 day');
    assert.equal(plural(3, 'day'), '3 days');
  });
});
