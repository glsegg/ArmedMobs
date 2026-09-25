// Minimal RCON client, used to drive the dev server from scripts.
// (Same protocol implementation as the sibling GirlsFrontline project's tools/rcon.js.)
//
//   node tools/rcon.js <host> <port> <password> "<command>" ["<command>" ...]
//
// Reading the commands from a file instead of the command line is what tools/build_city.ps1 uses
// for the ~1500 /setblock and /fill commands of the city preset:
//
//   node tools/rcon.js <host> <port> <password> --file tools/spike/work/city_commands.txt
const net = require('net');
const fs = require('fs');

const args = process.argv.slice(2);
let host = '127.0.0.1';
let port = '25577';
let password;
let commands = [];

if (args[0] === '--file') {
  password = args[1];
  commands = fs.readFileSync(args[2], 'utf8').split(/\r?\n/).filter((line) => line.trim().length > 0);
} else {
  [host, port, password, ...commands] = args;
}

if (!password) {
  console.error('usage: node tools/rcon.js <host> <port> <password> "<command>" ...');
  console.error('       node tools/rcon.js --file <password> <file>');
  process.exit(1);
}

const TYPE_RESPONSE = 0;
const TYPE_COMMAND = 2;
const TYPE_AUTH = 3;

function encode(id, type, body) {
  const payload = Buffer.from(body, 'utf8');
  const buffer = Buffer.alloc(4 + 4 + 4 + payload.length + 2);
  buffer.writeInt32LE(buffer.length - 4, 0);
  buffer.writeInt32LE(id, 4);
  buffer.writeInt32LE(type, 8);
  payload.copy(buffer, 12);
  buffer.writeInt16LE(0, 12 + payload.length);
  return buffer;
}

function connect() {
  return new Promise((resolve, reject) => {
    const socket = net.connect(Number(port), host, () => resolve(socket));
    socket.on('error', reject);
  });
}

/** Reads one RCON packet (or null on close). */
function readPacket(socket) {
  return new Promise((resolve) => {
    let buffer = Buffer.alloc(0);
    const onData = (chunk) => {
      buffer = Buffer.concat([buffer, chunk]);
      if (buffer.length < 4) return;
      const length = buffer.readInt32LE(0);
      if (buffer.length < 4 + length) return;
      socket.off('data', onData);
      resolve({
        id: buffer.readInt32LE(4),
        type: buffer.readInt32LE(8),
        body: buffer.subarray(12, 4 + length - 2).toString('utf8'),
      });
    };
    socket.on('data', onData);
    socket.on('close', () => resolve(null));
  });
}

async function main() {
  const socket = await connect();
  socket.write(encode(1, TYPE_AUTH, password));
  const auth = await readPacket(socket);
  if (!auth || auth.id === -1) {
    console.error('RCON auth failed');
    process.exit(1);
  }
  console.log('RCON connected');

  let id = 2;
  let index = 0;
  for (const command of commands) {
    socket.write(encode(id++, TYPE_COMMAND, command));
    const response = await readPacket(socket);
    index++;
    // A bulk build would flood the console; print every 50th command and everything that answered.
    const body = response && response.body ? response.body.trim() : '';
    if (commands.length > 20) {
      if (index % 50 === 0 || body.length > 0) {
        console.log(`[${index}/${commands.length}] ${command}${body ? ' -> ' + body : ''}`);
      }
    } else {
      console.log(`> ${command}`);
      console.log(body || '(no response)');
    }
  }

  socket.end();
}

main().catch((error) => {
  console.error('RCON error:', error.message);
  process.exit(1);
});
