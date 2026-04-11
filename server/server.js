const WebSocket = require('ws');
const { v4: uuidv4 } = require('uuid');

const PORT = process.env.PORT || 8080;
const wss = new WebSocket.Server({ port: PORT });

// Device registry: deviceId -> { ws, deviceName, online }
const devices = new Map();
// Room registry: roomId -> { controller: deviceId, controlled: deviceId }
const rooms = new Map();
// Pending connection requests: targetDeviceId -> { from, roomId, timestamp }
const pendingRequests = new Map();

// Connection code -> deviceId mapping (6-digit codes for easy pairing)
const connectionCodes = new Map();

function generateCode() {
  let code;
  do {
    code = String(Math.floor(100000 + Math.random() * 900000));
  } while (connectionCodes.has(code));
  return code;
}

function broadcast(deviceId, message) {
  const device = devices.get(deviceId);
  if (device && device.ws.readyState === WebSocket.OPEN) {
    device.ws.send(JSON.stringify(message));
  }
}

function cleanupDevice(deviceId) {
  const device = devices.get(deviceId);
  if (device) {
    // Remove connection code
    for (const [code, id] of connectionCodes.entries()) {
      if (id === deviceId) {
        connectionCodes.delete(code);
        break;
      }
    }
    // Clean up rooms
    for (const [roomId, room] of rooms.entries()) {
      if (room.controller === deviceId || room.controlled === deviceId) {
        const otherId = room.controller === deviceId ? room.controlled : room.controller;
        broadcast(otherId, { type: 'room_closed', roomId });
        rooms.delete(roomId);
      }
    }
    devices.delete(deviceId);
  }
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
      // Device registration
      case 'register': {
        const deviceId = msg.deviceId || uuidv4();
        currentDeviceId = deviceId;
        const code = generateCode();
        connectionCodes.set(code, deviceId);
        devices.set(deviceId, {
          ws,
          deviceName: msg.deviceName || 'Unknown',
          online: true,
        });
        ws.send(JSON.stringify({
          type: 'registered',
          deviceId,
          connectionCode: code,
        }));
        console.log(`Device registered: ${deviceId} (${msg.deviceName}) code=${code}`);
        break;
      }

      // Request to control another device by connection code
      case 'connect_request': {
        const targetDeviceId = connectionCodes.get(msg.code);
        if (!targetDeviceId) {
          ws.send(JSON.stringify({ type: 'error', message: 'Invalid connection code' }));
          return;
        }
        if (targetDeviceId === currentDeviceId) {
          ws.send(JSON.stringify({ type: 'error', message: 'Cannot connect to yourself' }));
          return;
        }
        const targetDevice = devices.get(targetDeviceId);
        if (!targetDevice || targetDevice.ws.readyState !== WebSocket.OPEN) {
          ws.send(JSON.stringify({ type: 'error', message: 'Target device offline' }));
          return;
        }
        const roomId = uuidv4();
        const controllerDevice = devices.get(currentDeviceId);
        pendingRequests.set(targetDeviceId, {
          from: currentDeviceId,
          roomId,
          timestamp: Date.now(),
        });
        // Ask the target device for permission
        broadcast(targetDeviceId, {
          type: 'control_request',
          roomId,
          fromDeviceId: currentDeviceId,
          fromDeviceName: controllerDevice ? controllerDevice.deviceName : 'Unknown',
        });
        ws.send(JSON.stringify({ type: 'connect_pending', roomId }));
        console.log(`Control request: ${currentDeviceId} -> ${targetDeviceId} room=${roomId}`);
        break;
      }

      // Target device accepts or rejects control request
      case 'control_response': {
        const pending = pendingRequests.get(currentDeviceId);
        if (!pending) {
          ws.send(JSON.stringify({ type: 'error', message: 'No pending request' }));
          return;
        }
        pendingRequests.delete(currentDeviceId);
        if (msg.accepted) {
          rooms.set(pending.roomId, {
            controller: pending.from,
            controlled: currentDeviceId,
          });
          broadcast(pending.from, {
            type: 'control_accepted',
            roomId: pending.roomId,
          });
          broadcast(currentDeviceId, {
            type: 'room_joined',
            roomId: pending.roomId,
            role: 'controlled',
          });
          console.log(`Room created: ${pending.roomId}`);
        } else {
          broadcast(pending.from, {
            type: 'control_rejected',
            roomId: pending.roomId,
          });
        }
        break;
      }

      // WebRTC signaling: offer, answer, ice_candidate
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
        broadcast(targetId, {
          type: msg.type,
          roomId: msg.roomId,
          data: msg.data,
        });
        break;
      }

      // Touch/input events from controller to controlled
      case 'input_event': {
        const room2 = rooms.get(msg.roomId);
        if (!room2 || room2.controller !== currentDeviceId) {
          ws.send(JSON.stringify({ type: 'error', message: 'Not authorized' }));
          return;
        }
        broadcast(room2.controlled, {
          type: 'input_event',
          roomId: msg.roomId,
          event: msg.event,
        });
        break;
      }

      // Disconnect from room
      case 'disconnect_room': {
        const room3 = rooms.get(msg.roomId);
        if (room3) {
          const otherId = room3.controller === currentDeviceId
            ? room3.controlled
            : room3.controller;
          broadcast(otherId, { type: 'room_closed', roomId: msg.roomId });
          rooms.delete(msg.roomId);
          console.log(`Room closed: ${msg.roomId}`);
        }
        break;
      }

      // Heartbeat
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

// Clean up stale pending requests every 30s
setInterval(() => {
  const now = Date.now();
  for (const [deviceId, req] of pendingRequests.entries()) {
    if (now - req.timestamp > 60000) {
      pendingRequests.delete(deviceId);
      broadcast(req.from, {
        type: 'control_timeout',
        roomId: req.roomId,
      });
    }
  }
}, 30000);

console.log(`Signaling server running on ws://0.0.0.0:${PORT}`);
