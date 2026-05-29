module.exports = {
  apps: [{
    name: 'network-guard-activation',
    script: 'server.js',
    cwd: __dirname,
    env: {
      ACTIVATION_PORT: 3002,
    },
    env_production: {
      ACTIVATION_PORT: 3002,
    },
    max_restarts: 10,
    restart_delay: 5000,
  }]
};
