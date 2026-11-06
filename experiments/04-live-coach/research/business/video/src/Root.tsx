import React from 'react';
import { Composition } from 'remotion';
import { Commercial } from './Commercial';

export const Root: React.FC = () => (
  <Composition id="Commercial" component={Commercial} durationInFrames={1800} fps={30} width={1920} height={1080} />
);
