const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

test('question waits out grace and browser TTS owns the handoff to answer mode', () => {
  const nodes = new Map();
  const makeNode = () => ({textContent:'', disabled:false, hidden:false, value:'1', checked:true, children:[],
    classList:{toggle(){}}, replaceChildren(...children){this.children=children;},
    append(...children){this.children.push(...children);}, prepend(){}, remove(){}});
  const element = id => { if (!nodes.has(id)) nodes.set(id, makeNode()); return nodes.get(id); };
  const timers = [];
  const source = fs.readFileSync(path.join(__dirname, '../../main/resources/web/integrated-app.js'), 'utf8');
  const utterances = [];
  const tts = {onvoiceschanged:null, getVoices:()=>[
    {name:'Русский A',lang:'ru-RU',voiceURI:'ru-a'},
    {name:'Русский B',lang:'ru-RU',voiceURI:'ru-b'},
    {name:'Русский C',lang:'ru-RU',voiceURI:'ru-c'}
  ], cancel(){}, speak:utterance=>utterances.push(utterance)};
  const context = {document:{getElementById:element, createElement:makeNode, createTextNode:text=>({textContent:text})},
    window:{speechSynthesis:tts}, speechSynthesis:tts, SpeechSynthesisUtterance:function(text){this.text=text;},
    setInterval(){}, setTimeout:(fn,ms)=>{timers.push({fn,ms}); return timers.length;}, clearTimeout(){},
    performance:{now:()=>0}, Date, console, fetch:async()=>({ok:true,json:async()=>({})})};
  vm.runInNewContext(source + '\nglobalThis.renderForTest = render; globalThis.setModeForTest = value => mode = value;', context);
  const status = (number, text, wait, retracted=false) => ({phase:'live', audio_seconds:1, integration:{
    question:{number,followUpNumber:text.startsWith('Уточнение')?1:0,text,level:'Стажёр',topic:'Java'},
    question_wait_ms:wait,decision_retracted:retracted}});
  context.setModeForTest('live');
  context.renderForTest(status(1, 'Первый вопрос', 0));
  assert.equal(element('question').textContent, 'Первый вопрос');
  assert.equal(element('asked').hidden, true);
  assert.equal(utterances.length, 1);
  assert.equal(utterances[0].text, 'Первый вопрос');
  assert.equal(utterances[0].voice.name, 'Русский A');
  assert.equal(timers.length, 0);
  context.renderForTest(status(1, 'Уточнение', 900));
  assert.equal(element('question').textContent, 'Уточнение');
  assert.equal(element('asked').disabled, true);
  assert.equal(timers.at(-1).ms, 925);
  timers.at(-1).fn();
  assert.equal(utterances.at(-1).voice.name, 'Русский A');
  context.renderForTest(status(1, 'Уточнение', 0, true));
  assert.match(element('tts-state').textContent, /продолжил ответ/);
  assert.equal(element('skip').disabled, true);

  context.renderForTest(status(2, 'Второй вопрос', 0));
  assert.equal(utterances.at(-1).voice.name, 'Русский B');
  context.renderForTest(status(2, 'Уточнение 2', 0));
  assert.equal(utterances.at(-1).voice.name, 'Русский B');
  context.renderForTest(status(3, 'Третий вопрос', 0));
  assert.equal(utterances.at(-1).voice.name, 'Русский C');
  const beforeManualQuestion = utterances.length;
  element('tts-enabled').checked = false;
  context.renderForTest(status(4, 'Четвёртый вопрос', 0));
  assert.equal(utterances.length, beforeManualQuestion);
  assert.equal(element('asked').hidden, false);
  assert.equal(element('asked').disabled, false);

  context.renderForTest({phase:'finished', integration:{report:{
    total:7.5, verdict:'Хорошо', answeredQuestions:2, plannedQuestions:2, finishedEarly:false,
    completeness:7, technical:8, terminology:6, speed:9,
    questions:[{number:1, topic:'Java Core', question:'Что такое JVM?', attempts:1,
      coveragePercent:80, completeness:8, technical:7, missing:['JRE']}]
  }}});
  assert.equal(element('final-report').hidden, false);
  assert.equal(element('report-total').textContent, '7,5 / 10');
  assert.equal(element('report-verdict').textContent, 'Хорошо');
  assert.match(element('report-counts').textContent, /2 из 2 вопросов/);
  assert.equal(element('report-questions').children.length, 1);
  assert.match(element('report-questions').children[0].children[0].textContent, /Java Core/);
});
