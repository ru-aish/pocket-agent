import test from 'node:test';
import assert from 'node:assert/strict';
import {renderMarkdown as sourceRender} from '../../runtime/preview-markdown.mjs';
import {renderMarkdown as bundledRender} from '../markdown-renderer.js';
import {renderMessageBody,reconcileMessages} from '../chat-view.js';

const sample = '# Title\n\n**Bold**, *italic*, ~~removed~~ and `inline`\n\n- one\n- two\n\n> a quote\n\n```js\nconst answer = 42;\n```\n\n| Name | Count |\n| --- | ---: |\n| Apple | 3 |';
test('headings, emphasis, lists, quotes, fenced code and tables render',()=>{
  const html=sourceRender(sample);
  for(const part of ['<h1>Title</h1>','<strong>Bold</strong>','<em>italic</em>','<s>removed</s>','<code>inline</code>','<ul>','<li>one</li>','<blockquote>','<pre>','<table>','<th','<td']) assert.ok(html.includes(part),`Missing ${part}`);
});
test('unclosed code fences remain safe and render incrementally',()=>{
  const html=sourceRender('Hello\n\n```ts\nconst x = 1');
  assert.match(html,/<pre><code class="language-ts">const x = 1/);
});
test('HTML, scripts, unsafe links and remote images cannot inject markup',()=>{
  const html=sourceRender('<script>alert(1)</script>\n\n<img src=x onerror=alert(1)>\n\n[bad](javascript:alert(1)) [data](data:text/html,hi)\n\n![pixel](https://example.org/tracker.png)');
  assert.doesNotMatch(html,/<script\b|<img\b|href="(?:javascript|data):/i);
  assert.match(html,/&lt;script&gt;/);
  assert.match(html,/\[Image: pixel\]/);
});
test('normal links open independently with secure rel attributes',()=>{
  const html=sourceRender('[example](https://example.com/doc)');
  assert.match(html,/<a href="https:\/\/example.com\/doc" target="_blank" rel="noopener noreferrer">example<\/a>/);
});
test('APK offline bundle has same Markdown behavior as source',()=>{
  assert.equal(bundledRender(sample),sourceRender(sample));
});
test('user message stays literal while assistant reply gets Markdown',()=>{
  const body={innerHTML:'',textContent:''};
  renderMessageBody(body,'**bold**<script>','user');
  assert.equal(body.textContent,'**bold**<script>');
  renderMessageBody(body,'**bold**<script>','assistant');
  assert.match(body.innerHTML,/<strong>bold<\/strong>/);
  assert.match(body.innerHTML,/&lt;script&gt;/);
});
test('reconcile caches original Markdown text instead of formatted textContent',()=>{
  class Element {
    constructor(tag){this.tagName=tag;this.children=[];this.className='';this.dataset={};this.textContent='';this.innerHTML='';this.parent=null;}
    append(...children){for(const child of children){child.parent=this;this.children.push(child);}}
    get firstElementChild(){return this.children[0];}
    get lastElementChild(){return this.children.at(-1);}
    querySelector(name){return this.children.find(child=>child.className===name.slice(1));}
    remove(){this.parent.children.splice(this.parent.children.indexOf(this),1);}
  }
  const original=globalThis.document;
  globalThis.document={createElement:tag=>new Element(tag)};
  try{
    const parent=new Element('div');
    assert.equal(reconcileMessages(parent,[{role:'assistant',text:'**bold**'}]),true);
    assert.match(parent.children[0].children[1].innerHTML,/<strong>bold<\/strong>/);
    assert.equal(reconcileMessages(parent,[{role:'assistant',text:'**bold**'}]),false);
    assert.equal(reconcileMessages(parent,[{role:'assistant',text:'**updated**'}]),true);
    assert.equal(reconcileMessages(parent,[]),true);
  } finally {globalThis.document=original;}
});
