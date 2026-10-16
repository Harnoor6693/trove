// Runs once before the workers start. Pinning a zone well ahead of UTC makes
// any "UTC date used as the local date" bug (B5-09, B5-10) fail on every
// machine, instead of only for developers who happen to live east of UTC.
module.exports = async () => {
  process.env.TZ = 'Pacific/Auckland';
};
