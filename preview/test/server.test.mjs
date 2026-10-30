import test from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp,rm} from 'node:fs/promises';
import {once} from 'node:events';
import {fileURLToPath} from 'node:url';

test('fresh preview serves the complete chat module graph and restricts provider requests',async t=>{
  const directory=await mkdtemp('/tmp/pocket-preview-http-');
  const child=spawn(process.execPath,[fileURLToPath(new URL('../server.mjs',import.meta.url))],{
    env:{...process.env,POCKET_PREVIEW_PORT:'0',POCKET_PREVIEW_STATE_DIR:directory},stdio:['ignore','pipe','pipe'],
  });
  t.after(async()=>{if(child.exitCode===null){child.kill('SIGTERM');await once(child,'exit');}await rm(directory,{recursive:true,force:true});});
  const base=await new Promise((resolve,reject)=>{
    const timer=setTimeout(()=>reject(Error('Preview did not start')),15000);
    child.on('error',error=>{clearTimeout(timer);reject(error);});
    child.on('exit',()=>{clearTimeout(timer);reject(Error('Preview exited before readiness'));});
    child.stdout.on('data',chunk=>{const match=String(chunk).match(/http:\/\/localhost:(\d+)/);if(match){clearTimeout(timer);resolve(`http://127.0.0.1:${match[1]}`);}});
  });
  const page=await fetch(base+'/');assert.equal(page.status,200);assert.match(page.headers.get('content-type'),/html/);assert.match(await page.text(),/<script(?=[^>]*type="module")(?=[^>]*src="\/app.js")[^>]*>/);
  const head=await fetch(base+'/events',{method:'HEAD',signal:AbortSignal.timeout(2000)});assert.ok(head.status<500);
  const pending=['/app.js'],seen=new Set();
  while(pending.length){
    const path=pending.shift();if(seen.has(path))continue;seen.add(path);
    const response=await fetch(base+path);assert.equal(response.status,200,`${path} must be served`);
    assert.match(response.headers.get('content-type'),/javascript/);
    const source=await response.text();
    for(const match of source.matchAll(/from\s*['"]\.\/([^'"]+)['"]/g))pending.push('/'+match[1]);
  }
  assert.ok(seen.has('/chat-view.js'),'Shared chat rendering must be reachable on a fresh page load');
  assert.equal((await fetch(base+'/api/provider/status')).status,403);
  assert.equal((await fetch(base+'/api/provider/status',{headers:{'X-Pocket-Request':'1'}})).status,200);
  assert.equal((await fetch(base+'/api/provider/status',{headers:{'X-Pocket-Request':'1',Origin:'https://example.com'}})).status,403);
});
