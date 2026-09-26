// Use the current ForgeGradle output instead of another developer's checkout/cache path.
const fs = require('fs');
const path = require('path');
module.exports = function resolveMinecraftJar(root) {
  for (const run of ['runClient', 'runServer']) {
    const file = path.join(root, 'build', 'classpath', `${run}_minecraftClasspath.txt`);
    if (!fs.existsSync(file)) continue;
    const jar = fs.readFileSync(file, 'utf8').split(/[;\r\n]+/)
      .map(entry => entry.trim()).find(entry => /forge-.*_mapped_.*\.jar$/.test(entry) && fs.existsSync(entry));
    if (jar) return jar;
  }
  return '';
};
