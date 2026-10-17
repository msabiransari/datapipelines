import {test} from 'node:test';
import assert from 'node:assert/strict';
import {restTreeSource,safeHref} from '../../main/resources/static/js/tree/rest-source.mjs';
import {assetPath} from '../../main/resources/static/js/page-assets.mjs';

const options={context:{workspace:'ws'},root:'scope'};
const response=data=>({ok:true,headers:{get:()=> 'application/json'},async json(){return {schema_version:1,data}}});
for(const family of ['pipelines','templates','dashboards','visualizations','parameter-sets']) {
  test(`${family} adapter binds parent, mode, root, query, workspace and no-store`,async()=>{
    let seen;
    const source=restTreeSource(family,async(url,init)=>{seen={url,init};return response({family,mode:'browse',root:'scope',parent:'scope',query:null,
      workspace_id:'ws',view_token:'members',nodes:[{key:'folder:scope/a',parent_key:null,kind:'folder',name:'a',path:'scope/a',has_children:true}],next_cursor:null});});
    const answer=await source.loadChildren(null,null,options,new AbortController().signal);
    assert.equal(answer.nodes[0].parentKey,null);assert.equal(answer.context.viewToken,'members');
    assert.equal(seen.init.cache,'no-store');assert.equal(seen.init.credentials,'same-origin');
    assert.equal(seen.url,`/api/v1/${family}/tree?root=scope&parent=scope`);
    const foreign=restTreeSource(family,async()=>response({family,mode:'browse',root:'scope',parent:'scope',query:null,workspace_id:'other',view_token:'members',nodes:[],next_cursor:null}));
    await assert.rejects(foreign.loadChildren(null,null,options),error=>error.resetTree===true);
    const expired=restTreeSource(family,async()=>({ok:false,status:400}));
    await assert.rejects(expired.loadChildren('folder:scope/a','old-process-cursor',options),error=>error.resetTree===true);
    for(const status of [401,403]) {
      const refused=restTreeSource(family,async()=>({ok:false,status}));
      await assert.rejects(refused.loadChildren(null,null,options),error=>error.resetTree===true);
    }
  });
}
test('a template leaf keeps the needs_review flag the payload carries (#533)',async()=>{
  const leaf=(key,flag)=>({key,parent_key:null,kind:'artifact',name:key,path:`scope/${key}`,has_children:false,match:false,
    resource_id:key,href:`/templates/scope/${key}`,version:1,draft_version:null,...(flag===undefined?{}:{needs_review:flag})});
  const source=restTreeSource('templates',async()=>response({family:'templates',mode:'browse',root:'scope',parent:'scope',query:null,
    workspace_id:'ws',view_token:'members',nodes:[leaf('drifted',true),leaf('clean',false),leaf('older',undefined),
      {key:'folder:scope/a',parent_key:null,kind:'folder',name:'a',path:'scope/a',has_children:true,match:false,needs_review:null}],next_cursor:null}));
  const answer=await source.loadChildren(null,null,options,new AbortController().signal);
  assert.deepEqual(answer.nodes.map(node=>node.needsReview),[true,false,undefined,null]);
});
test('a 400 search refusal carrying the shared query_too_long envelope names the limit (#513)',async()=>{
  const tooLong=restTreeSource('pipelines',async()=>({ok:false,status:400,headers:{get:()=> 'application/json'},async json(){return {error:{code:'request.query_too_long',
    message:'That search term is too long. Search with 200 characters or fewer.',details:{limit:200,length:201}}}}}));
  await assert.rejects(tooLong.search('a'.repeat(201),null,options),
    error=>error.notice===true&&error.message.includes('200')&&error.message!=='Could not load tree');
});
test('a 400 search refusal without a usable limit or code stays the generic error (#513)',async()=>{
  for(const body of [undefined,{error:{code:'request.bad',details:{limit:200}}},
    {error:{code:'request.query_too_long',details:{limit:'lots'}}},{error:{code:'request.query_too_long',details:{limit:0}}},
    {error:{code:'request.query_too_long',details:{limit:-200}}},{error:{code:'request.query_too_long',details:{}}},{error:{code:'request.query_too_long'}}]) {
    const refused=restTreeSource('pipelines',async()=>({ok:false,status:400,headers:{get:()=> 'application/json'},async json(){return body}}));
    await assert.rejects(refused.search('x',null,options),error=>error.message==='Could not load tree'&&error.notice===undefined);
  }
  const opaque=restTreeSource('pipelines',async()=>({ok:false,status:400}));
  await assert.rejects(opaque.search('x',null,options),error=>error.message==='Could not load tree'&&error.notice===undefined);
});
test('a 400 WITH a cursor never reads the body and still resets the tree (#513)',async()=>{
  let read=false;
  const expired=restTreeSource('pipelines',async()=>({ok:false,status:400,headers:{get:()=> 'application/json'},
    async json(){read=true;return {error:{code:'request.query_too_long',details:{limit:200,length:201}}}}}));
  await assert.rejects(expired.loadChildren('folder:scope/a','old-process-cursor',options),error=>error.resetTree===true);
  assert.equal(read,false);
});
test('unsafe destinations and dependency paths refuse schemes, traversal and foreign routes',()=>{
  for(const href of ['https://elsewhere/pipelines/id','//elsewhere/pipelines/id','/templates/id','/pipelines/../admin','/pipelines/%2e%2e/admin','/pipelines/.%2e/admin','/pipelines/%invalid','/pipelines/%2fadmin','/pipelines/id?data=1','/pipelines/id#x'])assert.equal(safeHref(href,'pipelines'),false);
  assert.equal(safeHref('/pipelines/id','pipelines'),true);
  for(const path of ['https://elsewhere/js/code.js','/js/../secret.js','/api/v1/x.js','/js/code.js?url=secret'])assert.throws(()=>assetPath(path));
  assert.equal(assetPath('/vendor/plotly/plotly-3d.min.js'),'/vendor/plotly/plotly-3d.min.js');
});
