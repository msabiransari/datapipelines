import {test} from 'node:test';
import assert from 'node:assert/strict';
import {maximumRailWidth, effectiveWidth} from '../../main/resources/static/js/rail-resize.mjs';

test('the column rail maximum reserves the main token and keeps the rail floor', () => {
  assert.equal(maximumRailWidth(1280, 320), 960);
  assert.equal(maximumRailWidth(500, 320), 232);
  assert.equal(maximumRailWidth(768, 320), 448);
  assert.equal(effectiveWidth(2000, 960, 232), 960);
});
