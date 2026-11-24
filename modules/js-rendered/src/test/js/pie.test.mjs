// Pie's model and geometry contract (brief 1068 D4-D11). Oracles use the delivered rows and primitive output.
import test from 'node:test';
import assert from 'node:assert/strict';
import {engine, inputOf} from './engine.mjs';
import {textBox} from './geometry.mjs';
import {createRequire} from 'node:module';
const I = createRequire(import.meta.url)('../../../../web/src/main/resources/static/js/datapipelines-chart-interactions.js')._internal;

function fixture(values, options = {}) {
  return {document: {config: {mark: {type: 'pie', ...options.mark}, encoding: {
    theta: {field: 'v', type: 'quantitative', aggregate: 'sum', ...options.theta},
    color: {field: 's', type: 'nominal', sort: null, ...options.color},
  }}}, columns: {s: options.names || values.map((_, i) => 'S' + (i + 1)), v: values}, types: {s: 'STRING', v: options.type || 'DOUBLE'}};
}
function model(R, f) {return R.pieModel(f.document, f.columns, f.types);}
function pieEngine() {return engine(s => {s['types/pie.js'] = s['types/pie.js'].replace('R.type("pie",', 'R.pieModel = model; R.type("pie",').replace('marks: marks, axes:', 'marks: function(m,hand,h) { R.pieHand=hand; R.pieHelpers=h; return marks(m,hand,h); }, axes:');});}
function draw(R, f, extra = {}) {return R.render(inputOf(f, 960, 540, extra));}

test('pie groups rows, drops null categories once, retains zeros, refuses negative aggregates', () => {
  const R = pieEngine();
  const f = fixture([2, 3, 0, null, 4, 5], {names: ['A', 'A', 'B', 'C', null, null]});
  const m = model(R, f);
  assert.deepEqual(Array.from(m.slices, s => [s.id, s.num]), [['string:A', 5], ['string:B', 0]]);
  assert.equal(m.markCount, 1);
  assert.equal(m.warnings.filter(w => w.code === 'chart.rule.null-category').length, 1);
  assert.equal(draw(R, fixture([1, -1])).summary.refusal.code, 'chart.rule.negative-part');
  assert.equal(draw(R, fixture([0, 0])).state, 'no-data');
});

test('pie rejects overflow totals before hidden state and drops overflowing Other once', () => {
  const R = pieEngine();
  for (const hidden of [[], ['string:S1'], ['string:S2']]) {
    const r = draw(R, fixture([1e308, 1e308]), {hidden});
    assert.equal(r.state, 'no-data');
    assert.equal(r.summary.marks, 0);
    assert.equal(r.summary.warnings.filter(w => w.code === 'chart.rule.non-finite').length, 1);
  }
  const m = model(R, fixture([1e308, 1e308, 1e308], {mark: {max_slices: 2}}));
  assert.equal(m.slices.length, 1);
  assert.equal(m.warnings.filter(w => w.code === 'chart.rule.non-finite').length, 1);
});

test('pie folds the smallest aggregates, keeps order and ties, counts Other in the limit', () => {
  const R = pieEngine();
  const m = model(R, fixture(Array.from({length: 12}, (_, i) => i + 1)));
  assert.deepEqual(Array.from(m.slices, s => s.num), [6, 7, 8, 9, 10, 11, 12, 15]);
  assert.equal(m.slices.at(-1).id, 'other');
  assert.equal(m.series.length, 8);
  assert.equal(draw(R, fixture(Array(9).fill(1), {mark:{max_slices:9}})).summary.refusal.code,'chart.too_many_series');
  assert.equal(draw(R, fixture(Array(8).fill(1))).summary.marks,8);
  assert.deepEqual(Array.from(model(R, fixture([1, 1, 1, 1], {mark: {max_slices: 3}})).slices, s => s.id), ['string:S1', 'string:S2', 'other']);
});

test('pie angles renormalize drawn slices while text retains the shown total', () => {
  const R = pieEngine();
  const r = draw(R, fixture([1, ...Array(6).fill(0.00005), 1]));
  assert.deepEqual(Array.from(r.index.marks, m => [m.shape.from, m.shape.to]), [[0, 180], [180, 360]]);
  const exact = draw(R, fixture(['1000000000', '1'], {type: 'BIGINTEGER'}));
  const seven = draw(R, fixture(Array(7).fill(1)));
  assert.equal(seven.index.marks.at(-1).shape.to,360);
  assert.equal(exact.index.marks.length, 1);
  assert.match(JSON.stringify(exact.primitives), /1,000,000,001/);
});

test('pie uses half-even whole percentages in labels and finer shares in cards', () => {
  const R = pieEngine();
  const r = draw(R, fixture([1, 7]));
  assert.match(JSON.stringify(r.primitives), /12%/);
  assert.doesNotMatch(JSON.stringify(r.primitives), /13%/);
  assert.equal(r.index.marks[0].card.rows[0].value, '1 (12.5%)');
  assert.ok(r.index.marks.every(m => m.row === 0 && m.emphasis === 'move' && m.shape.kind === 'sector'));
  assert.equal(R.tables.look('pieMoveAllowance'), R.tables.look('markMove'));
});

test('pie null and non-finite aggregates, count, exact sums, sort and Other identity', () => {
  const R = pieEngine();
  const m = model(R, fixture([null, Infinity, NaN, 0]));
  assert.deepEqual(Array.from(m.slices, s => s.num), [0]);
  assert.equal(m.warnings.filter(w => w.code === 'chart.rule.non-finite').length, 1);
  assert.equal(model(R, fixture([null, null], {names: ['A', 'A'], theta: {aggregate: 'count'}})).slices[0].num, 2);
  const exact = model(R, fixture(Array(10).fill('9007199254740993'), {type: 'BIGINTEGER'}));
  assert.equal(exact.slices.at(-1).exact, '27021597764222979');
  const ownOther = model(R, fixture([100, 3, 2, 1], {names: ['Other', 'A', 'B', 'C'], mark: {max_slices: 2}}));
  assert.deepEqual(Array.from(ownOther.slices, s => s.id), ['string:Other', 'other']);
  for (const sort of [null, 'ascending', 'descending', ['S4', 'S2', 'S1', 'S3']]) {
    const f = fixture([1, 2, 3, 4, 5], {mark: {max_slices: 4}, color: {sort}});
    const m = model(R, f);
    assert.equal(m.series.length, 4);
    assert.equal(m.slices.at(-1).num, 3);
    assert.ok(m.slices.slice(0, -1).every(s => s.num >= 3));
    assert.equal(m.series.at(-1).colour, '--chart-series-4');
  }
  for (const hidden of [[], ['string:S1'], ['string:S2']]) {
    const r = draw(R, fixture([1e308, 5e307]), {hidden});
    assert.equal(r.state, 'rendered');
    assert.equal(r.summary.warnings.length, 0);
    assert.doesNotMatch(JSON.stringify(r.primitives), /NaN|Infinity/);
  }
});

test('pie lookup radii, card, square legend, keys and hidden reproportioning', () => {
  const R = pieEngine(), f = fixture([50, 30, 15, 5], {theta: {unit: 'kg'}}), r = draw(R, f);
  for (const [i, m] of r.index.marks.entries()) {
    const s = m.shape, a = (s.from + s.to) * Math.PI / 360, radius = (s.r0 + s.r1) / 2;
    assert.equal(R.lookup.at(r.index, s.cx + Math.sin(a) * radius, s.cy - Math.cos(a) * radius, 0), i);
    assert.equal(R.lookup.at(r.index, s.cx, s.cy, 0), -1);
    assert.equal(R.lookup.at(r.index, s.cx + Math.sin(a) * (s.r1 + 0.1), s.cy - Math.cos(a) * (s.r1 + 0.1), 0), -1);
    assert.equal(m.card.heading, null);
    assert.equal(m.card.keep, 0);
    const target = {kind: 'mark', id: m.series, datum: m.datum};
    const lm = I.modelOf(R, r, R.measure.measurer(null).fn);
    assert.equal(I.keyTarget(lm, target, 'ArrowDown').target.datum, m.datum);
    assert.equal(I.keyTarget(lm, target, 'Home').target.datum, 'string:S1');
    assert.equal(I.keyTarget(lm, target, 'End').target.datum, 'string:S4');
    const right = I.keyTarget(lm, target, 'ArrowRight').target;
    assert.equal(right.kind, i === 3 ? 'entry' : 'mark');
    if (i < 3) assert.equal(right.datum, 'string:S' + (i + 2));
  }
  assert.equal(r.index.marks[0].card.rows[0].value, '50 kg (50%)');
  assert.ok(r.primitives.some(p => p.k === 'rect' && p.role === 'legend'));
  const hidden = draw(R, f, {hidden: ['string:S1']});
  assert.equal(hidden.index.marks[0].shape.to, 216);
  assert.equal(hidden.index.marks[0].card.rows[0].value, '30 kg (60%)');
  const all = draw(R, f, {hidden: r.summary.series.map(s => s.id)});
  assert.equal(all.summary.marks, 0);
  assert.ok(all.primitives.some(p => p.role === 'legend'));
  assert.ok(!draw(R, fixture([1])).primitives.some(p => p.role === 'legend'));
  const two = draw(R, fixture([1, 1])), s = two.index.marks[0].shape;
  assert.equal(R.lookup.at(two.index, s.cx, s.cy + (s.r0 + s.r1) / 2, 0), 1);
});

// Independent containment: polar angle and signed distance to each radial boundary, with the SVG-rounded box.
function inside(x, y, s, pad = 0) {
  const dx = x - s.cx, dy = y - s.cy, r = Math.hypot(dx, dy), rad = Math.PI / 180;
  if (r < s.r0 + pad - 0.05 || r > s.r1 - pad + 0.05) return false;
  if (s.to - s.from === 360) return true;
  const angle = (Math.atan2(dx, -dy) / rad + 360) % 360;
  if (angle + 0.01 < s.from || angle - 0.01 > s.to) return false;
  const a = dx * Math.cos(s.from * rad) + dy * Math.sin(s.from * rad);
  const b = -dx * Math.cos(s.to * rad) - dy * Math.sin(s.to * rad);
  return s.to - s.from <= 180 ? Math.min(a, b) >= pad - 0.05 : Math.max(a, b) >= pad - 0.05;
}

test('pie labels clear their sectors at rest and moved, while the reserved ink stays in the frame', () => {
  const R = pieEngine(); let labels = 0, missing = 0, sectors = 0, errors = 0;
  for (const edge of [0.5, 0.3]) {
    R.tables.LOOK.edge.value = edge;
    for (const inner of [0, 0.2, 0.4, 0.6, 0.7, 0.8]) for (const mode of ['percent', 'value']) {
      const f = fixture([30, 22, 15, 12, 9, 6, 4, 2], {mark: {inner, labels: mode}});
      for (let w = 240; w <= 1600; w += 40) for (const h of [100, 140, 200, 240, 480]) {
        const r = R.render(inputOf(f, w, h));
        for (const m of r.index.marks) {
          const s = m.shape, mid = (s.from + s.to) * Math.PI / 360, mx = 5 * Math.sin(mid), my = -5 * Math.cos(mid);
          const expectedR = Math.round((R.pieHand.radius - Math.max(0, 6 - edge * 12)) * 100) / 100;
          assert.equal(s.r1, expectedR);
          assert.equal(s.r0, Math.round(s.r1 * inner * 100) / 100);
          const p = r.primitives.find(p => p.k === 'path' && p.datum === m.datum);
          assert.equal(p.stroke, '--chart-canvas'); assert.equal(p.strokeWidth, 2); assert.equal(p.round, true);
          for (let a = s.from; a <= s.to; a += Math.max(0.02, (s.to - s.from) / 40)) {
            const x = s.cx + mx + s.r1 * Math.sin(a * Math.PI / 180), y = s.cy + my - s.r1 * Math.cos(a * Math.PI / 180);
            if (x - 1 < -0.1 || x + 1 > w + 0.1 || y - 1 < -0.1 || y + 1 > h + 0.1) errors++;
          }
          const label = r.primitives.find(p => p.k === 'text' && p.series === m.series && p.role === 'mark');
          if (label) {
            labels++;
            for (const [x, y] of textBox(label)) {
              assert.ok(inside(x, y, s, 4), 'rest ' + JSON.stringify({w,h,s,label}));
              assert.ok(inside(x - mx, y - my, s, 4), 'moved ' + JSON.stringify({w,h,s,label}));
            }
          } else missing++;
          sectors++;
        }
      }
    }
  }
  console.log(`pie fit: ${labels} labels, ${missing} omitted, ${sectors} sectors, ${errors} moved ink bounds violations`);
  assert.ok(labels >= 10000); assert.ok(missing > 0); assert.equal(errors, 0);
});

function ratio(a, b) {
  const lum = css => (css.startsWith('#') ? [1,3,5].map(i=>parseInt(css.slice(i,i+2),16)) : css.match(/\d+/g).slice(0,3).map(Number)).map(x => x/255).map(x => x <= 0.04045 ? x/12.92 : ((x+0.055)/1.055)**2.4).reduce((n,x,i) => n+x*[0.2126,0.7152,0.0722][i],0);
  const x=lum(a),y=lum(b);return (Math.max(x,y)+0.05)/(Math.min(x,y)+0.05);
}
test('pie label inks follow both resolved themes, swapped inks and custom series colours', () => {
  const R = pieEngine(), f = fixture([30,22,15,12,9,6,4,2]); let seen=0;
  for(const base of ['light','dark']) for(const tokens of [{}, {'--chart-on-fill-light':[17,20,24],'--chart-on-fill-dark':[255,255,255]}, {'--chart-series-1':[250,250,250]}, {'--chart-series-1':[5,5,5]}]) {
    const r=draw(R,f,{theme:{base,tokens}});
    for(const p of r.primitives.filter(p=>p.k==='text'&&p.series&&p.role==='mark')) {
      const fill=r.primitives.find(q=>q.k==='path'&&q.series===p.series);
      assert.ok(ratio(p.color,fill.color)>=4.5, JSON.stringify({p,fill}));seen++;
    }
  }
  assert.ok(seen>=50);
  for(const base of ['light','dark']) {
    const d=R.theme.defaults(base), normal=draw(R,f,{theme:base}), swapped=draw(R,f,{theme:{base,tokens:{'--chart-on-fill-light':d['--chart-on-fill-dark'],'--chart-on-fill-dark':d['--chart-on-fill-light']}}});
    for(const p of normal.primitives.filter(p=>p.k==='text'&&p.role==='mark'&&p.series)) {
      const q=swapped.primitives.find(q=>q.k==='text'&&q.role==='mark'&&q.series===p.series);assert.notEqual(q.fill,p.fill);assert.equal(q.color,p.color);
    }
  }
  for(const values of [[1,2],[15,63],[1,7],[7,1],[1,199],[1,9999]]) {
    const r=draw(R,fixture(values)), label=r.primitives.find(p=>p.k==='text'&&p.role==='mark'&&p.series==='string:S1');
    const want=values[1]===2?'33%':values[0]===15?'19%':values[0]===7?'88%':values[1]===7?'12%':null;
    assert.equal(label?.text ?? null,want);
  }
  assert.ok(!R.render(inputOf(f,80,24)).primitives.some(p=>p.k==='text'));
  assert.ok(!R.render(inputOf(f,160,160)).primitives.some(p=>p.k==='text'));
  assert.ok(!draw(R,fixture([1,2],{mark:{labels:'none',inner:0}})).primitives.some(p=>p.k==='text'&&p.role==='mark'));
});

test('pie inner cubic boundaries stay within 0.05 px, and a ring winds around the hole zero times', () => {
  const R = pieEngine(); let samples=0;
  for(const values of [[1],[999,1],[1,1],[1,1,1,1]]) {
    const r=R.render(inputOf(fixture(values),1600,960));
    for(const m of r.index.marks) {
      const p=r.primitives.find(p=>p.k==='path'&&p.datum===m.datum), s=m.shape;
      let pos=0,current=null;const poly=[];
      while(pos<p.d.length) {
        const op=p.d[pos++], arity=R.prim.ARITY[op], args=Array.from(p.d.slice(pos,pos+arity));pos+=arity;
        if(op==='M'||op==='L') {current=args;poly.push(args);}
        if(op==='A') {
          const end=args.slice(1,3), startAngle=Math.atan2(current[0]-s.cx,-(current[1]-s.cy));
          let span=Math.atan2(end[0]-s.cx,-(end[1]-s.cy))-startAngle;
          while(span<=0) span+=2*Math.PI;
          for(let j=1;j<=100;j++) {const a=startAngle+span*j/100;poly.push([s.cx+args[0]*Math.sin(a),s.cy-args[0]*Math.cos(a)]);}
          current=end;
        }
        if(op==='C') {
          const start=current,end=args.slice(4,6);
          for(let j=0;j<=100;j++) {
            const t=j/100,u=1-t,x=u*u*u*start[0]+3*u*u*t*args[0]+3*u*t*t*args[2]+t*t*t*end[0];
            const y=u*u*u*start[1]+3*u*u*t*args[1]+3*u*t*t*args[3]+t*t*t*end[1];
            assert.ok(Math.abs(Math.hypot(x-s.cx,y-s.cy)-s.r0)<=0.05,'inner edge');samples++;poly.push([x,y]);
          }
          current=end;
        }
      }
      const points=poly;
      function winding(x,y) {
        let n=0;
        for(let i=0;i<points.length;i++) {const a=points[i],b=points[(i+1)%points.length],cross=(b[0]-a[0])*(y-a[1])-(x-a[0])*(b[1]-a[1]);if(a[1]<=y&&b[1]>y&&cross>0)n++;if(a[1]>y&&b[1]<=y&&cross<0)n--;}
        return n;
      }
      const a=(s.from+s.to)*Math.PI/360,mid=(s.r0+s.r1)/2;
      assert.equal(winding(s.cx,s.cy),0);
      assert.equal(winding(s.cx+mid*Math.sin(a),s.cy-mid*Math.cos(a)),1);
    }
  }
  assert.ok(samples>1000);
});

test('pie hole threshold is strict, unit is dropped first, exact total and minimal rules hold', () => {
  const R=pieEngine();
  for(const [r0,shown] of [[33.9,false],[34,false],[34.1,true]]) {
    const f=fixture([1],{mark:{labels:'none',inner:0.6}});
    // Minimal's frame has only the two six-pixel edges; use compact height 140 with an authored inner for exact r0.
    const first=R.render(inputOf(f,200,140)),radius=first.index.marks[0].shape.r1;
    f.document.config.mark.inner=r0/radius;
    const r=R.render(inputOf(f,200,140));
    assert.equal(r.primitives.some(p=>p.k==='text'&&p.role==='mark'&&!p.series),shown);
  }
  const exact=draw(R,fixture(['9007199254740993','9007199254740993'],{type:'BIGINTEGER',mark:{labels:'none'}}));
  assert.ok(exact.primitives.some(p=>p.text==='18,014,398,509,481,986'));
  const decimal=draw(R,fixture(['0.10','0.20','0.30'],{type:'BIGDECIMAL',mark:{labels:'value'}}));
  assert.ok(decimal.primitives.some(p=>p.text==='0.6'));
  const f=fixture([1],{theta:{unit:'a very long unit'},mark:{labels:'none'}});
  const r=R.render(inputOf(f,200,140));
  assert.ok(r.primitives.some(p=>p.text==='1'&&p.role==='mark'));
  assert.ok(!r.primitives.some(p=>p.text==='a very long unit'));
});

test('pie LA2 each token reaches only its governed paint fields', () => {
  const R=pieEngine(),f=fixture([30,22,15,12,9,6,4,2],{theta:{unit:'kg'}});
  f.document.config.title={text:'Parts of a whole'};
  const render=tokens=>R.render(inputOf(f,1600,960,{theme:{base:'light',tokens}})).primitives;
  const base=render({}), changes={
    '--chart-series-1':[250,250,250], '--chart-canvas':[210,220,230], '--chart-gap':3,
    '--chart-text-small':12, '--chart-title-weight':700, '--chart-title':15, '--chart-text':13,
    '--chart-ink':[10,20,30], '--chart-ink-2':[40,50,60], '--chart-on-fill-light':[245,245,245], '--chart-on-fill-dark':[10,10,10],
  };
  for(const [token,value] of Object.entries(changes)) {
    const next=render({[token]:value}); assert.equal(next.length,base.length,token+' primitive count'); let changed=0;
    if(token==='--chart-canvas') for(const p of next.filter(p=>p.k==='path'&&p.role==='mark')) assert.equal(p.strokeColor,R.theme.resolve({base:'light',tokens:{[token]:value}}).css(token));
    for(let i=0;i<base.length;i++) for(const key of ['color','fill','strokeColor','strokeWidth','size','weight']) {
      const a=base[i],b=next[i]; if(JSON.stringify(a[key])===JSON.stringify(b[key])) continue; changed++;
      const label=a.role==='mark'&&a.k==='text'&&a.series;
      const permitted=(key==='color'&&(a.fill===token || (label&&(token.startsWith('--chart-on-fill-')||token==='--chart-series-1'))))
        || (key==='fill'&&label&&(token.startsWith('--chart-on-fill-')||token==='--chart-series-1'))
        || (key==='strokeColor'&&a.stroke===token) || (key==='strokeWidth'&&token==='--chart-gap'&&a.k==='path')
        || (key==='weight'&&token==='--chart-title-weight'&&a.weight===600)
        || (key==='size'&&((token==='--chart-text-small'&&label)||(token==='--chart-title'&&a.size===14)||(token==='--chart-text'&&a.size===12)));
      assert.ok(permitted,token+' changed '+key+' of '+JSON.stringify(a));
    }
    assert.ok(changed>0,token+' was dead');
  }
});
