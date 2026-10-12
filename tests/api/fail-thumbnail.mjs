import sharp from 'sharp';

// Inject an image-processor failure into the API subprocess without a production test hook.
sharp.prototype.resize = function () { throw new Error('Synthetic thumbnail failure'); };
