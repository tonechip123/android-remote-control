const http = require('http');
const fs = require('fs');
const path = require('path');
const WebSocket = require('ws');
const { v4: uuidv4 } = require('uuid');

const PORT = process.env.PORT || 8080;
const APK_PATH = path.join(__dirname, '..', 'dl', 'my.apk');

// HTTP server: serves APK at /my.apk, upgrades WebSocket otherwise
const httpServer = http.createServer((req, res) => {
  const dlDir = path.join(__dirname, '..', 'dl');
  if (req.url === '/my.apk' || req.url === '/rustdesk.apk') {
    const filename = req.url.slice(1);
    const file = path.join(dlDir, filename);
    if (fs.existsSync(file)) {
      const stat = fs.statSync(file);
      res.writeHead(200, {
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Length': stat.size,
        'Content-Disposition': `attachment; filename="${filename}"`,
      });
      fs.createReadStream(file).pipe(res);
    } else {
      res.writeHead(404);
      res.end('APK not found');
    }
  } else {
    res.writeHead(200);
    res.end('Signaling server OK');
  }
});

const wss = new WebSocket.Server({ server: httpServer });

// Device registry: deviceId -> { ws, deviceName, online }
const devices = new Map();
// Room registry: roomId -> { controller: deviceId, controlled: deviceId }
const rooms = new Map();

function sendTo(deviceId, message) {
  const device = devices.get(deviceId);
  if (device && device.ws.readyState === WebSocket.OPEN) {
    device.ws.send(JSON.stringify(message));
  }
}

// Broadcast online device list to all connected devices
function broadcastDeviceList() {
  const list = [];
  for (const [id, dev] of devices.entries()) {
    list.push({ deviceId: id, deviceName: dev.deviceName });
  }
  const msg = JSON.stringify({ type: 'device_list', devices: list });
  for (const [, dev] of devices.entries()) {
    if (dev.ws.readyState === WebSocket.OPEN) {
      dev.ws.send(msg);
    }
  }
}

function cleanupDevice(deviceId) {
  // Clean up rooms
  for (const [roomId, room] of rooms.entries()) {
    if (room.controller === deviceId || room.controlled === deviceId) {
      const otherId = room.controller === deviceId ? room.controlled : room.controller;
      sendTo(otherId, { type: 'room_closed', roomId });
      rooms.delete(roomId);
    }
  }
  devices.delete(deviceId);
  broadcastDeviceList();
}

wss.on('connection', (ws) => {
  let currentDeviceId = null;

  ws.on('message', (data) => {
    let msg;
    try {
      msg = JSON.parse(data);
    } catch (e) {
      ws.send(JSON.stringify({ type: 'error', message: 'Invalid JSON' }));
      return;
    }

    switch (msg.type) {
      case 'register': {
        const deviceId = msg.deviceId || uuidv4();
        currentDeviceId = deviceId;
        devices.set(deviceId, {
          ws,
          deviceName: msg.deviceName || 'Unknown',
        });
        ws.send(JSON.stringify({ type: 'registered', deviceId }));
        console.log(`Device registered: ${deviceId} (${msg.deviceName})`);
        broadcastDeviceList();
        break;
      }

      // Direct connect by deviceId (no code needed)
      case 'connect_to': {
        const targetDeviceId = msg.targetDeviceId;
        if (!targetDeviceId || targetDeviceId === currentDeviceId) {
          ws.send(JSON.stringify({ type: 'error', message: 'Invalid target' }));
          return;
        }
        const targetDevice = devices.get(targetDeviceId);
        if (!targetDevice || targetDevice.ws.readyState !== WebSocket.OPEN) {
          ws.send(JSON.stringify({ type: 'error', message: 'Target device offline' }));
          return;
        }
        const roomId = uuidv4();
        rooms.set(roomId, {
          controller: currentDeviceId,
          controlled: targetDeviceId,
        });
        // Auto-accept: notify both sides directly
        sendTo(currentDeviceId, {
          type: 'control_accepted',
          roomId,
        });
        sendTo(targetDeviceId, {
          type: 'room_joined',
          roomId,
          role: 'controlled',
        });
        console.log(`Room created: ${roomId} (${currentDeviceId} -> ${targetDeviceId})`);
        break;
      }

      // WebRTC signaling
      case 'offer':
      case 'answer':
      case 'ice_candidate': {
        const room = rooms.get(msg.roomId);
        if (!room) {
          ws.send(JSON.stringify({ type: 'error', message: 'Room not found' }));
          return;
        }
        const targetId = room.controller === currentDeviceId
          ? room.controlled
          : room.controller;
        sendTo(targetId, {
          type: msg.type,
          roomId: msg.roomId,
          data: msg.data,
        });
        break;
      }

      // Touch/input events
      case 'input_event': {
        const room2 = rooms.get(msg.roomId);
        if (!room2 || room2.controller !== currentDeviceId) {
          ws.send(JSON.stringify({ type: 'error', message: 'Not authorized' }));
          return;
        }
        sendTo(room2.controlled, {
          type: 'input_event',
          roomId: msg.roomId,
          event: msg.event,
        });
        break;
      }

      case 'disconnect_room': {
        const room3 = rooms.get(msg.roomId);
        if (room3) {
          const otherId = room3.controller === currentDeviceId
            ? room3.controlled
            : room3.controller;
          sendTo(otherId, { type: 'room_closed', roomId: msg.roomId });
          rooms.delete(msg.roomId);
          console.log(`Room closed: ${msg.roomId}`);
        }
        break;
      }

      case 'ping': {
        ws.send(JSON.stringify({ type: 'pong' }));
        break;
      }

      default:
        ws.send(JSON.stringify({ type: 'error', message: `Unknown type: ${msg.type}` }));
    }
  });

  ws.on('close', () => {
    if (currentDeviceId) {
      console.log(`Device disconnected: ${currentDeviceId}`);
      cleanupDevice(currentDeviceId);
    }
  });

  ws.on('error', (err) => {
    console.error(`WebSocket error: ${err.message}`);
    if (currentDeviceId) {
      cleanupDevice(currentDeviceId);
    }
  });
});

httpServer.listen(PORT, () => {
  console.log(`Server running on http://0.0.0.0:${PORT}`);
  console.log(`APK download: http://0.0.0.0:${PORT}/my.apk`);
  console.log(`WebSocket signaling: ws://0.0.0.0:${PORT}`);
});
