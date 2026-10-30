import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import {syncBuiltinESMExports} from 'node:module';
import {join} from 'node:path';
import {createVault} from '../vault.mjs';
async function temporary(t){const dir=await fs.mkdtemp('/tmp/pocket-vault-fault-');t.after(()=>fs.rm(dir,{recursive:true,force:true}));return dir;}
async function faultOpen(t,predicate,method,work){
 const original=fs.open;
 const patch=t.mock.method(fs,'open',async(...args)=>{const handle=await original(...args);if(predicate(args[0]))t.mock.method(handle,method,async()=>{throw Error('synthetic storage failure');});return handle;});
 syncBuiltinESMExports();
 try{return await work();}finally{patch.mock.restore();syncBuiltinESMExports();}
}
test('failed key sync never publishes a partial vault key',async t=>{
 const dir=await temporary(t);
 await faultOpen(t,path=>String(path).includes('.vault-key-'),'sync',()=>assert.rejects(createVault(dir),/synthetic/));
 assert.deepEqual(await fs.readdir(dir),[]);
 const vault=await createVault(dir);await vault.update(state=>{state.account={token:'recoverable'};});
 assert.equal((await createVault(dir)).get('account').token,'recoverable');
});
test('failed credential writes remove temporary snapshots and retain prior credentials',async t=>{
 const dir=await temporary(t),vault=await createVault(dir);await vault.update(state=>{state.account={token:'prior'};});
 await faultOpen(t,path=>String(path).includes('.credentials-'),'writeFile',()=>assert.rejects(vault.update(state=>{state.account={token:'rejected'};}),/synthetic/));
 assert.deepEqual((await fs.readdir(dir)).sort(),['credentials.enc','vault.key']);
 assert.equal((await createVault(dir)).get('account').token,'prior');
});
test('directory sync failure after rename keeps in-memory credentials aligned with the committed file',async t=>{
 const dir=await temporary(t),vault=await createVault(dir);
 await faultOpen(t,path=>path===dir,'sync',()=>assert.rejects(vault.update(state=>{state.first={token:'committed'};}),/synthetic/));
 assert.equal(vault.get('first').token,'committed');
 await vault.update(state=>{state.second={token:'later'};});
 const reopened=await createVault(dir);assert.equal(reopened.get('first').token,'committed');assert.equal(reopened.get('second').token,'later');
});
