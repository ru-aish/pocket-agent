import test from 'node:test';
import assert from 'node:assert/strict';
import {messageBlocks,tableCells} from '../chat-view.js';
test('pipe tables have header, body and column alignment between text blocks',()=>{
 const blocks=messageBlocks('Before\n\n| Item | Amount |\n| :--- | ---: |\n| Apple | 12 |\n| Pear | 3 |\n\nAfter');
 assert.deepEqual(blocks[1],{type:'table',header:['Item','Amount'],rows:[['Apple','12'],['Pear','3']],align:['left','right']});
 assert.match(blocks[0].text,/Before/);assert.match(blocks[2].text,/After/);
});
test('escaped pipes and inline code remain inside cells',()=>{
 assert.deepEqual(tableCells('| a\\|b | `x|y` |'),['a|b','`x|y`']);
});
test('fenced code and incomplete streaming separators stay plain text',()=>{
 assert.equal(messageBlocks('```\n| A | B |\n| --- | --- |\n| 1 | 2 |\n```')[0].type,'text');
 assert.equal(messageBlocks('| A | B |\n| -- |')[0].type,'text');
});
test('HTML in table data remains literal data for textContent rendering',()=>{
 assert.equal(messageBlocks('| A | B |\n| --- | --- |\n| <img src=x onerror=alert(1)> | safe |')[0].rows[0][0],'<img src=x onerror=alert(1)>');
});
