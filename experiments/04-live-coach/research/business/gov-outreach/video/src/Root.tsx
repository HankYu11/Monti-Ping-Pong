import React from 'react';
import { Composition } from 'remotion';
import { GovPitch, StillServeComp, StillSetupComp } from './GovPitch';

export const Root: React.FC = () => (
  <>
    <Composition id="GovPitch" component={GovPitch} durationInFrames={1800} fps={30} width={1920} height={1080} />
    <Composition id="StillSetup" component={StillSetupComp} durationInFrames={1} fps={30} width={1500} height={1000} />
    <Composition id="StillServe" component={StillServeComp} durationInFrames={1} fps={30} width={1500} height={1000} />
  </>
);
