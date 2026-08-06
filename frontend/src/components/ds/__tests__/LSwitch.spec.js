import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import LSwitch from '../LSwitch.vue';

describe('LSwitch', () => {
  it('renders a real checkbox so it is keyboard reachable', () => {
    const wrapper = mount(LSwitch, { props: { label: 'Deep Scan' } });
    const input = wrapper.find('input[type="checkbox"]');
    expect(input.exists()).toBe(true);
  });

  it('reflects modelValue', () => {
    const wrapper = mount(LSwitch, { props: { modelValue: true, label: 'Deep Scan' } });
    expect(wrapper.find('input').element.checked).toBe(true);
    expect(wrapper.find('.l-switch-track').classes()).toContain('active');
  });

  it('emits update:modelValue and change when toggled', async () => {
    const wrapper = mount(LSwitch, { props: { modelValue: false, label: 'Deep Scan' } });
    await wrapper.find('input').setValue(true);
    expect(wrapper.emitted('update:modelValue')[0]).toEqual([true]);
    expect(wrapper.emitted('change')[0]).toEqual([true]);
  });

  it('does not emit when disabled', async () => {
    const wrapper = mount(LSwitch, { props: { modelValue: false, label: 'X', disabled: true } });
    await wrapper.find('input').trigger('change');
    expect(wrapper.emitted('update:modelValue')).toBeUndefined();
  });

  it('marks the input disabled at the markup level when disabled', () => {
    const wrapper = mount(LSwitch, { props: { label: 'X', disabled: true } });
    expect(wrapper.find('input').element.disabled).toBe(true);
  });

  it('associates the label with the input', () => {
    const wrapper = mount(LSwitch, { props: { label: 'Deep Scan' } });
    expect(wrapper.find('label').text()).toBe('Deep Scan');
    expect(wrapper.find('input').element.closest('label')).toBeTruthy();
  });

  it('names the input via aria-label when the visible text lives outside', () => {
    const wrapper = mount(LSwitch, { props: { ariaLabel: 'Deep Scan' } });
    expect(wrapper.find('input').attributes('aria-label')).toBe('Deep Scan');
    expect(wrapper.find('.l-switch-label').exists()).toBe(false);
  });
});
