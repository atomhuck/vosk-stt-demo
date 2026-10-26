const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function processor() {
  let Processor;
  class AudioWorkletProcessor {
    constructor() { this.port = {messages: [], postMessage: message => this.port.messages.push(message)}; }
  }
  const source = fs.readFileSync(path.join(__dirname, '../../main/resources/web/integrated-worklet.js'), 'utf8');
  vm.runInNewContext(source, {
    AudioWorkletProcessor, sampleRate: 16000,
    registerProcessor: (name, value) => { assert.equal(name, 'mono-pcm'); Processor = value; }
  });
  return new Processor();
}

function feed(p, system, microphone) {
  const a = [new Float32Array(128).fill(system)];
  const b = [new Float32Array(128).fill(microphone)];
  for (let i = 0; i < 80; i++) p.process([a, b], [[new Float32Array(128)]]);
  return p.port.messages.find(message => message.type === 'audio');
}

test('enrollment mixes microphone and system audio in 160 ms mono frames', () => {
  const p = processor();
  p.port.onmessage({data: 'enroll'});
  const event = feed(p, .25, .75);
  assert.equal(event.buffer.byteLength, 5120);
  assert.ok(new Int16Array(event.buffer).every(x => Math.abs(x - 16383) <= 2));
});

test('interview mixes mic and system audio to one mono 160 ms frame', () => {
  const p = processor();
  p.port.onmessage({data: 'live'});
  const event = feed(p, .25, .75);
  assert.equal(event.buffer.byteLength, 5120);
  assert.ok(new Int16Array(event.buffer).every(x => Math.abs(x - 16383) <= 2));
});
