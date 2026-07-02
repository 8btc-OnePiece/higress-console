import i18n from '@/i18n';
import { fetchPluginsByRoute } from '@/interfaces/route';
import { PluginPhase, WasmPluginData } from '@/interfaces/wasm-plugin';
import { getDomainPluginInstances, getGatewayRouteDetail, getGlobalPluginInstances, getWasmPlugins } from '@/services';
import { CaretRightOutlined, EllipsisOutlined } from '@ant-design/icons';
import { useRequest } from 'ahooks';
import { Avatar, Button, Card, Col, Dropdown, Empty, Popconfirm, Tag, Typography } from 'antd';
import { useSearchParams } from 'ice';
import { forwardRef, useEffect, useImperativeHandle, useMemo, useState } from 'react';
import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import { getI18nValue, QueryType } from '../../utils';
import PluginCategory from '../PluginCategory';
import { BUILTIN_ROUTE_PLUGIN_LIST, DEFAULT_PLUGIN_IMG } from './constant';
import styles from './index.module.css';

const { Paragraph } = Typography;
const { Meta } = Card;

const CATEGORY_KEYS = [
  'route',
  'ai',
  'auth',
  'security',
  'traffic',
  'transform',
  'o11y',
  'custom',
];

const DEFAULT_PRIORITY = 0;
const PHASE_ORDER: PluginPhase[] = [PluginPhase.AUTHN, PluginPhase.AUTHZ, PluginPhase.STATS, PluginPhase.UNSPECIFIED];
const PHASE_WEIGHT: Record<PluginPhase, number> = {
  [PluginPhase.AUTHN]: 0,
  [PluginPhase.AUTHZ]: 1,
  [PluginPhase.STATS]: 2,
  [PluginPhase.UNSPECIFIED]: 3,
};
const DEFAULT_PHASE_ALIASES: Record<string, PluginPhase> = {
  DEFAULT: PluginPhase.UNSPECIFIED,
  UNSPECIFIED: PluginPhase.UNSPECIFIED,
};
const PHASE_ALIASES: Record<string, PluginPhase> = PHASE_ORDER.reduce((result, phase) => {
  result[phase] = phase;
  return result;
}, DEFAULT_PHASE_ALIASES);

const PHASE_I18N_KEY: Record<PluginPhase, string> = {
  [PluginPhase.AUTHN]: 'plugins.phases.authn',
  [PluginPhase.AUTHZ]: 'plugins.phases.authz',
  [PluginPhase.STATS]: 'plugins.phases.stats',
  [PluginPhase.UNSPECIFIED]: 'plugins.phases.unspecified',
};

const getPluginKey = (plugin: WasmPluginData) => plugin.key || plugin.name || `${plugin.imageRepository}:${plugin.imageVersion}`;

const getPluginCategory = (plugin: WasmPluginData) => plugin.category || 'custom';

const normalizePhase = (phase?: string) => PHASE_ALIASES[(phase || '').toUpperCase()] || PluginPhase.UNSPECIFIED;

const getPluginPriority = (plugin: WasmPluginData) => plugin.priority ?? DEFAULT_PRIORITY;

const getPluginAnchorId = (plugin: WasmPluginData) => {
  const key = getPluginKey(plugin);
  return `plugin-card-${encodeURIComponent(key)}`;
};

const getPhaseLabel = (phase: string | undefined, t: TFunction) => t(PHASE_I18N_KEY[normalizePhase(phase)]);

const sortPluginsByExecutionOrder = (pluginList: WasmPluginData[]) => {
  return [...pluginList].sort((a, b) => {
    const phaseDiff = PHASE_WEIGHT[normalizePhase(a.phase)] - PHASE_WEIGHT[normalizePhase(b.phase)];
    if (phaseDiff !== 0) {
      return phaseDiff;
    }

    const priorityDiff = getPluginPriority(b) - getPluginPriority(a);
    if (priorityDiff !== 0) {
      return priorityDiff;
    }

    const aStableKey = `${a.namespace || ''}/${a.name || getPluginKey(a)}`;
    const bStableKey = `${b.namespace || ''}/${b.name || getPluginKey(b)}`;
    return aStableKey.localeCompare(bStableKey);
  });
};

interface Props {
  data: Object;
  onOpen: (v: Object) => void;
  onEdit?: (v: WasmPluginData) => void;
  onDelete?: (v: string) => void;
}

export interface ListRef {
  refresh: () => void;
}

const PluginList = forwardRef((props: Props, ref) => {
  const { t } = useTranslation();
  const { data, onOpen, onEdit, onDelete } = props;
  const [searchParams] = useSearchParams();

  const type = searchParams.get('type') || '';

  const HIDDEN_PLUGINS_BY_QUERY_TYPE = {};
  HIDDEN_PLUGINS_BY_QUERY_TYPE[QueryType.AI_ROUTE] = ['ai-proxy', 'key-auth', 'model-router', 'model-mapper'];

  const handleClickPlugin = (item) => {
    onOpen(item);
  };

  const [pluginList, setPluginList] = useState<WasmPluginData[]>([]);
  const [activeCategoryKeys, setActiveCategoryKeys] = useState<string[]>(CATEGORY_KEYS);
  const [collapsedPhaseMap, setCollapsedPhaseMap] = useState<Record<string, boolean>>({});

  const { loading, run: loadWasmPlugins } = useRequest(() => {
    return getWasmPlugins(i18n.language);
  }, {
    manual: true,
    onSuccess: async (result = []) => {
      let plugins = result || [];
      const name = searchParams.get('name');
      if (type && !name) {
        // If the type is specified but no name is provided, we cannot proceed
        return;
      }
      if (type === QueryType.ROUTE || type === QueryType.AI_ROUTE) {
        let builtInRoutePluginList = BUILTIN_ROUTE_PLUGIN_LIST;
        let routeName = name;
        if (type === QueryType.AI_ROUTE) {
          routeName = `ai-route-${routeName}.internal`;
          builtInRoutePluginList = builtInRoutePluginList.filter(p => p.enabledInAiRoute !== false);
        }
        const currentRoute = await getGatewayRouteDetail(routeName);
        if (!currentRoute) {
          plugins = builtInRoutePluginList.concat(plugins);
        } else {
          const pluginByRoutes = await fetchPluginsByRoute(currentRoute);
          const builtInPlugins: WasmPluginData[] = builtInRoutePluginList.map((plugin) => {
            const foundPlugin = pluginByRoutes.find((p) => p.name === plugin.key && p.internal);
            return {
              ...plugin,
              name: plugin.key,
              enabled: foundPlugin ? foundPlugin.enabled : false,
            };
          });
          const updatedPlugins = result.map((plugin: { name: string }) => {
            const foundPlugin = pluginByRoutes.find((p) => p.name === plugin.name);
            return {
              ...plugin,
              enabled: foundPlugin ? foundPlugin.enabled : false,
            };
          });
          plugins = builtInPlugins.concat(updatedPlugins);
        }
      } else if (type === QueryType.DOMAIN) {
        const pluginsByDomain = await getDomainPluginInstances(name);
        plugins = result.map((plugin: { name: string }) => {
          const foundPlugin = pluginsByDomain.find((p: { pluginName: string }) => p.pluginName === plugin.name);
          return {
            ...plugin,
            enabled: foundPlugin ? foundPlugin.enabled : false,
          };
        });
      } else {
        const pluginsByGlobal = await getGlobalPluginInstances();
        plugins = result.map((plugin: { name: string }) => {
          const foundPlugin = pluginsByGlobal.find((p: { pluginName: string }) => p.pluginName === plugin.name);
          return {
            ...plugin,
            enabled: foundPlugin ? foundPlugin.enabled : false,
          };
        });
      }
      const hiddenPlugins = HIDDEN_PLUGINS_BY_QUERY_TYPE[type];
      if (Array.isArray(hiddenPlugins)) {
        plugins = plugins.filter(p => !(p.builtIn && hiddenPlugins.includes(p.name)));
      }
      setPluginList(plugins);
    },
  });

  useImperativeHandle(ref, () => {
    return {
      refresh: () => {
        loadWasmPlugins();
      },
    }
  });

  useEffect(() => {
    loadWasmPlugins();
    const handler = () => loadWasmPlugins();
    i18n.on('languageChanged', handler);
    return () => {
      i18n.off('languageChanged', handler);
    };
  }, []);

  const createPluginDropdown = (plugin) => {
    if (BUILTIN_ROUTE_PLUGIN_LIST.some(p => p.key === plugin.key)) {
      return null;
    }
    const items = [
      {
        key: 'edit',
        label: (
          <span
            onClick={() => {
              onEdit?.(plugin);
            }}
          >
            {t('misc.edit')}
          </span>
        ),
      },
    ];
    if (!plugin.builtIn) {
      items.push({
        key: 'delete',
        label: (
          <Popconfirm
            title={t('plugins.deleteConfirmation')}
            onConfirm={() => {
              onDelete?.(plugin.name);
            }}
          >
            <span style={{ color: '#ff4d4f' }}>{t('misc.delete')}</span>
          </Popconfirm>
        ),
      });
    }
    return (
      <Dropdown
        menu={{
          items,
        }}
      >
        <EllipsisOutlined />
      </Dropdown>
    )
  };

  const executionGroups = useMemo(() => {
    const sortedPlugins = sortPluginsByExecutionOrder(pluginList);
    return PHASE_ORDER.map(phase => ({
      phase,
      plugins: sortedPlugins.filter(plugin => normalizePhase(plugin.phase) === phase),
    }));
  }, [pluginList]);

  const handleLocatePlugin = (plugin: WasmPluginData) => {
    const category = getPluginCategory(plugin);
    setActiveCategoryKeys(keys => (keys.includes(category) ? keys : [...keys, category]));
    window.setTimeout(() => {
      document.getElementById(getPluginAnchorId(plugin))?.scrollIntoView({
        behavior: 'smooth',
        block: 'center',
      });
    }, 120);
  };

  const handleTogglePhase = (phase: PluginPhase) => {
    setCollapsedPhaseMap(prev => ({
      ...prev,
      [phase]: !prev[phase],
    }));
  };

  const renderExecutionSidebar = () => (
    <aside className={styles.executionSidebar}>
      <div className={styles.sidebarHeader}>
        <div>
          <div className={styles.sidebarTitle}>{t('plugins.executionOrder.title')}</div>
          <div className={styles.sidebarSubtitle}>{t('plugins.executionOrder.subtitle')}</div>
        </div>
      </div>
      <div className={styles.phaseFlow}>
        {PHASE_ORDER.map((phase, index) => (
          <div key={phase} className={styles.phaseFlowItem}>
            <span className={styles.phaseStep}>{index + 1}</span>
            <span className={styles.phaseFlowLabel}>{getPhaseLabel(phase, t)}</span>
          </div>
        ))}
      </div>
      <div className={styles.sidebarContent}>
        {pluginList.length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('plugins.noPlugins')} />
        ) : (
          executionGroups.map((group, index) => (
            <section key={group.phase} className={styles.phaseSection}>
              <button
                type="button"
                className={styles.phaseSectionHeader}
                onClick={() => handleTogglePhase(group.phase)}
                aria-expanded={!collapsedPhaseMap[group.phase]}
              >
                <span className={styles.phaseIndex}>{index + 1}</span>
                <span className={styles.phaseName}>{getPhaseLabel(group.phase, t)}</span>
                <span className={styles.phaseCount}>{group.plugins.length}</span>
                <CaretRightOutlined
                  rotate={collapsedPhaseMap[group.phase] ? 0 : 90}
                  className={styles.phaseToggleIcon}
                />
              </button>
              {index < PHASE_ORDER.length - 1 && <div className={styles.phaseConnector} />}
              {!collapsedPhaseMap[group.phase] && (
                <div className={styles.phasePluginList}>
                  {group.plugins.length > 0 ? (
                    group.plugins.map(plugin => (
                      <button
                        type="button"
                        key={getPluginKey(plugin)}
                        className={styles.executionItem}
                        onClick={() => handleLocatePlugin(plugin)}
                      >
                        <span className={styles.executionItemMain}>
                          <span className={styles.executionItemTitle}>{getI18nValue(plugin, 'title') || plugin.name}</span>
                          <span className={styles.executionItemName}>{plugin.name}</span>
                        </span>
                        <span className={styles.executionItemMeta}>
                          <span className={styles.priorityBadge}>{t('plugins.executionOrder.priorityShort')}{getPluginPriority(plugin)}</span>
                          {plugin.enabled && <span className={styles.enabledDot}>{t('plugins.enabled')}</span>}
                        </span>
                      </button>
                    ))
                  ) : (
                    <div className={styles.emptyPhase}>{t('plugins.executionOrder.emptyPhase')}</div>
                  )}
                </div>
              )}
            </section>
          ))
        )}
      </div>
    </aside>
  );

  // Render a single plugin card
  const renderPluginItem = (item: WasmPluginData) => {
    const key = item.key || `${item.name}:${item.imageVersion}`;
    return (
      <Col span={6} key={key} xl={8} lg={12} md={12} sm={12} xs={24}>
        <Card
          id={getPluginAnchorId(item)}
          className={styles.pluginCard}
          hoverable
          actions={[
            <div key="configure" onClick={() => handleClickPlugin(item)}>
              <Button type="text" size="small">
                {t('misc.configure')}
              </Button>
            </div>,
          ]}
        >
          <Meta
            avatar={
              <Avatar
                size={'large'}
                src={item?.icon || DEFAULT_PLUGIN_IMG}
                style={{
                  opacity: item?.icon ? '0.5' : '0.2',
                  border: '1px solid #ddd',
                  padding: '8px',
                }}

              />
            }
            title={
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <div style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {getI18nValue(item, 'title')}
                </div>
                {
                  item.enabled && (
                    <Tag
                      color="green"
                      style={{ marginLeft: 6, fontSize: '10px', lineHeight: '16px', padding: '0 4px', borderRadius: '2px' }}
                    >{t('plugins.enabled')}
                    </Tag>
                  )
                }
                {
                  createPluginDropdown(item)
                }
              </div>
            }
            description={
              <>
                <div className={styles.pluginMeta}>
                  <span className={styles.metaItem}>
                    <span className={styles.metaLabel}>{t('plugins.executionOrder.phase')}</span>
                    <span>{getPhaseLabel(item.phase, t)}</span>
                  </span>
                  <span className={styles.metaItem}>
                    <span className={styles.metaLabel}>{t('plugins.executionOrder.priority')}</span>
                    <span>{getPluginPriority(item)}</span>
                  </span>
                </div>
                <Paragraph ellipsis={{ rows: 3 }} style={{ minHeight: '64px', color: '#00000073', marginBottom: 0 }}>
                  {getI18nValue(item, 'description')}
                </Paragraph>
              </>
            }
          />
        </Card>
      </Col>
    );
  };

  // Get categories from translations
  const categoryList = useMemo(() => {
    return CATEGORY_KEYS.map(key => ({
      key,
      label: t(`plugins.categories.${key}`),
    }));
  }, [t]);

  return (
    <div className={styles.pluginListLayout}>
      <div className={styles.pluginListMain}>
        <PluginCategory
          pluginList={pluginList}
          renderPluginItem={renderPluginItem}
          categoryList={categoryList}
          activeKeys={activeCategoryKeys}
          onActiveKeysChange={setActiveCategoryKeys}
        />
      </div>
      <div className={styles.pluginListSide}>
        {renderExecutionSidebar()}
      </div>
    </div>
  );
});

export default PluginList;
