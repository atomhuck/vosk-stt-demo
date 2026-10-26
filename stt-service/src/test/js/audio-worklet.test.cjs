const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function makeProcessor() {
  let Processor;
  class AudioWorkletProcessor {
    constructor() { this.port = {messages: [], postMessage: message => this.port.messages.push(message)}; }
  }
  const source = fs.readFileSync(path.join(__dirname, '../../main/resources/web/audio-worklet.js'), 'utf8');
  vm.runInNewContext(source, {
    AudioWorkletProcessor, sampleRate: 16000,
    registerProcessor: (name, value) => { assert.equal(name, 'mono-pcm'); Processor = value; }
  });
  return new Processor();
}

function feed(processor, system, microphone) {
  const systemFrame = [new Float32Array(128).fill(system), new Float32Array(128).fill(system)];
  const micFrame = [new Float32Array(128).fill(microphone)];
  for (let i = 0; i < 80; i++) processor.process([systemFrame, micFrame], [[new Float32Array(128)]]);
  return processor.port.messages.find(message => message.type === 'audio');
}

test('enrollment ignores microphone and emits one 0.64-second PCM chunk', () => {
  const processor = makeProcessor();
  processor.port.onmessage({data: 'enroll'});
  const output = feed(processor, .25, .9);
  assert.equal(output.buffer.byteLength, 10240 * 2);
  const samples = new Int16Array(output.buffer);
  assert.ok(samples.every(value => Math.abs(value - 8192) <= 1));
});

test('interview mixes both audio sources', () => {
  const processor = makeProcessor();
  processor.port.onmessage({data: 'live'});
  const output = feed(processor, .25, .75);
  const samples = new Int16Array(output.buffer);
  assert.ok(samples.every(value => Math.abs(value - 16384) <= 1));
});
