import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import StatusPill from '../StatusPill.vue';

describe('StatusPill', () => {
  it('defaults to starting so it never claims a connection it has not verified', () => {
    const wrapper = mount(StatusPill);
    expect(wrapper.classes()).toContain('starting');
    expect(wrapper.text()).toBe('Backend: Starting...');
  });

  it.each([
    ['online', 'Backend: Online'],
    ['starting', 'Backend: Starting...'],
    ['offline', 'Backend: Offline'],
  ])('renders %s', (status, expected) => {
    const wrapper = mount(StatusPill, { props: { status } });
    expect(wrapper.classes()).toContain(status);
    expect(wrapper.text()).toBe(expected);
  });

  it('uses a custom label', () => {
    const wrapper = mount(StatusPill, { props: { status: 'online', label: 'Engine' } });
    expect(wrapper.text()).toBe('Engine: Online');
  });

  it('announces changes to assistive tech', () => {
    const wrapper = mount(StatusPill);
    expect(wrapper.attributes('role')).toBe('status');
    expect(wrapper.attributes('aria-live')).toBe('polite');
  });

  it('hides the decorative dot from assistive tech', () => {
    const wrapper = mount(StatusPill);
    expect(wrapper.find('.status-dot').attributes('aria-hidden')).toBe('true');
  });
});
