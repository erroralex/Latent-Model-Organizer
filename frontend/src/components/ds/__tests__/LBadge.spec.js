import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import LBadge from '../LBadge.vue';

describe('LBadge', () => {
  it.each(['neutral', 'accent', 'success', 'warning', 'danger', 'outline'])(
    'renders the %s variant declared by the design system',
    (variant) => {
      const wrapper = mount(LBadge, { props: { variant }, slots: { default: 'X' } });
      expect(wrapper.classes()).toContain(variant);
    }
  );

  it('defaults to neutral md', () => {
    const wrapper = mount(LBadge, { slots: { default: 'X' } });
    expect(wrapper.classes()).toEqual(expect.arrayContaining(['l-badge', 'neutral', 'md']));
  });

  it('renders slot content', () => {
    const wrapper = mount(LBadge, { slots: { default: 'DRY RUN' } });
    expect(wrapper.text()).toBe('DRY RUN');
  });

  it('supports the sm size', () => {
    const wrapper = mount(LBadge, { props: { size: 'sm' }, slots: { default: 'X' } });
    expect(wrapper.classes()).toContain('sm');
  });
});
