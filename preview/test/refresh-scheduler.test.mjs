import test from 'node:test';
import assert from 'node:assert/strict';
import {createRefreshScheduler} from '../refresh-scheduler.js';
function clock(){let next=0;const jobs=new Map();return {jobs,setTimer(fn){const id=++next;jobs.set(id,fn);return id;},clearTimer(id){jobs.delete(id);},async tick(){const list=[...jobs.values()];jobs.clear();await Promise.all(list.map(fn=>fn()));}};}
test('bursts coalesce and updates during a read produce one trailing refresh',async()=>{
 const c=clock();let reads=0,release;const refresh=createRefreshScheduler(()=>{reads++;return new Promise(resolve=>{release=resolve;});},c);
 for(let i=0;i<100;i++)refresh();assert.equal(c.jobs.size,1);
 const pending=c.tick();assert.equal(reads,1);
 for(let i=0;i<100;i++)refresh();assert.equal(c.jobs.size,0);
 release();await pending;assert.equal(c.jobs.size,1);
 const trailing=c.tick();assert.equal(reads,2);release();await trailing;assert.equal(c.jobs.size,0);
});
test('cancel removes pending work and a later update can resume refreshes',async()=>{
 const c=clock();let reads=0;const refresh=createRefreshScheduler(async()=>{reads++;},c);
 refresh();refresh.cancel();await c.tick();assert.equal(reads,0);
 refresh();await c.tick();assert.equal(reads,1);
});
test('cancelling while a read is running suppresses its queued trailing read',async()=>{
 const c=clock();let release;const refresh=createRefreshScheduler(()=>new Promise(resolve=>{release=resolve;}),c);
 refresh();const pending=c.tick();refresh();refresh.cancel();release();await pending;assert.equal(c.jobs.size,0);
});
test('refresh failures release the scheduler for the next update',async()=>{
 const c=clock();let reads=0;const refresh=createRefreshScheduler(async()=>{if(++reads===1)throw Error('offline');},c);
 refresh();await assert.rejects(c.tick(),/offline/);refresh();await c.tick();assert.equal(reads,2);
});
